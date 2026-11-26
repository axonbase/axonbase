package com.axonbase.core.qos;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.core.Session;
import com.axonbase.core.engine.QueryContext;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;

/**
 * Prioridade por tipo de carga: cada consulta de cliente passa por {@link #admit} antes de executar. As analíticas
 * esperam vaga e, enquanto há trabalho transacional recente, cedem processador nos pontos em que o motor já confere
 * cancelamento ({@link QueryContext#checkCancelled}).
 */
public final class WorkloadGovernor {

    /** Acessos ao armazenamento entre duas conferências do orçamento, por thread. */
    static final int STRIDE = 64;
    /** Depois da última consulta transacional, por quanto tempo as analíticas ainda cedem. */
    static final long TRANSACTIONAL_WINDOW_NANOS = 200_000_000L;

    /** A admissão de uma consulta; fechá-la libera a vaga. */
    public interface Ticket extends AutoCloseable {
        @Override
        void close();
    }

    private static final Ticket UNGOVERNED = () -> { };

    /** Uma consulta que chama outra (saga, programa) não passa duas vezes: ela esperaria pela própria vaga. */
    private static final ThreadLocal<Boolean> INSIDE = new ThreadLocal<>();

    private static final ThreadLocal<Stride> STRIDES = ThreadLocal.withInitial(Stride::new);

    private static final class Stride {
        Object owner;
        int accesses;
        long mark;
    }

    private final QosSettings settings;
    private final LongSupplier clock;
    private final LongConsumer pause;
    private final AnalyticalSlots slots;
    private final CpuBudget budget;
    private final AtomicInteger transactionalRunning = new AtomicInteger();
    private final AtomicInteger analyticalRunning = new AtomicInteger();
    private final AtomicLong transactionalAdmitted = new AtomicLong();
    private final AtomicLong analyticalAdmitted = new AtomicLong();
    private final AtomicLong demoted = new AtomicLong();
    private final AtomicLong budgetExceeded = new AtomicLong();
    private final LongAdder throttleNanos = new LongAdder();
    private final LatencyHistogram transactionalLatency = new LatencyHistogram();
    private final LatencyHistogram analyticalLatency = new LatencyHistogram();
    private volatile long lastTransactionalEnd;
    private volatile boolean sawTransactional;

    public WorkloadGovernor(QosSettings settings) {
        this(settings, System::nanoTime, LockSupport::parkNanos);
    }

    WorkloadGovernor(QosSettings settings, LongSupplier clock, LongConsumer pause) {
        this.settings = settings;
        this.clock = clock;
        this.pause = pause;
        this.slots = new AnalyticalSlots(settings.concurrency(), settings.queue(), settings.queueTimeoutMillis());
        this.budget = new CpuBudget(settings.cores() * settings.cpuPercent() / 100.0, clock);
    }

    public QosSettings settings() {
        return settings;
    }

    /**
     * Admite uma consulta. Quem chama executa e fecha o retorno na mesma thread.
     *
     * @throws AxonError {@link QosCodes#REJECTED} quando uma analítica não consegue vaga
     */
    public Ticket admit(String sql, Session session) {
        if (!settings.enabled() || INSIDE.get() != null) {
            return UNGOVERNED;
        }
        QueryContext.Handle current = QueryContext.current();
        if (current != null && current.pacer() != null) {
            return UNGOVERNED;
        }
        // sem consulta registrada (REST de tabela, GraphQL, uso embarcado) cria uma só para o orçamento
        QueryContext.Handle handle = current != null ? current : new QueryContext.Handle("qos");
        WorkloadClassifier.Decision decision =
            WorkloadClassifier.classify(settings, sql, session, handle.requestedWorkload());
        boolean analytical = decision == WorkloadClassifier.Decision.ANALYTICAL;
        if (analytical) {
            slots.acquire(handle);
            analyticalRunning.incrementAndGet();
            analyticalAdmitted.incrementAndGet();
        } else {
            transactionalRunning.incrementAndGet();
            transactionalAdmitted.incrementAndGet();
        }
        QueryContext.Handle previous = current == null ? QueryContext.attach(handle) : null;
        Admitted admitted = new Admitted(handle, current == null, previous, analytical,
            decision == WorkloadClassifier.Decision.DEMOTABLE);
        INSIDE.set(Boolean.TRUE);
        handle.pacer(admitted);
        return admitted;
    }

    /** Há consulta transacional em curso ou que terminou há pouco. */
    boolean transactionalActive() {
        return transactionalRunning.get() > 0
            || sawTransactional && clock.getAsLong() - lastTransactionalEnd < TRANSACTIONAL_WINDOW_NANOS;
    }

    public QosStats stats() {
        return new QosStats(transactionalRunning.get(), analyticalRunning.get(), slots.queued(),
            transactionalAdmitted.get(), analyticalAdmitted.get(), slots.rejectedQueueFull(),
            slots.rejectedTimeout(), demoted.get(), budgetExceeded.get(), slots.waitNanos(), throttleNanos.sum(),
            transactionalLatency.snapshot(), analyticalLatency.snapshot());
    }

    /** Uma consulta admitida: o bilhete dela e o ponto de contenção que o motor chama. */
    private final class Admitted implements Ticket, QueryContext.Pacer {
        private final QueryContext.Handle handle;
        private final boolean attached;
        private final QueryContext.Handle previous;
        private final boolean holdsSlot;
        private final boolean demotable;
        private final long startedNanos = clock.getAsLong();
        private final AtomicLong accesses = new AtomicLong();
        private final AtomicBoolean closed = new AtomicBoolean();
        private volatile boolean analytical;
        private volatile long analyticalSince;

        Admitted(QueryContext.Handle handle, boolean attached, QueryContext.Handle previous, boolean analytical,
                 boolean demotable) {
            this.handle = handle;
            this.attached = attached;
            this.previous = previous;
            this.holdsSlot = analytical;
            this.demotable = demotable;
            this.analytical = analytical;
            this.analyticalSince = startedNanos;
        }

        @Override
        public void checkpoint() {
            Stride stride = STRIDES.get();
            if (stride.owner != this) {
                stride.owner = this;
                stride.accesses = 0;
                stride.mark = clock.getAsLong();
            }
            if (++stride.accesses < STRIDE) {
                return;
            }
            stride.accesses = 0;
            if (!analytical) {
                if (!demotable || accesses.addAndGet(STRIDE) < settings.scanThreshold()) {
                    return;
                }
                demote();
            }
            charge(stride);
        }

        private synchronized void demote() {
            if (analytical || closed.get()) {
                return;
            }
            analyticalSince = clock.getAsLong();
            analytical = true;
            demoted.incrementAndGet();
            transactionalRunning.decrementAndGet();
            analyticalRunning.incrementAndGet();
        }

        private void charge(Stride stride) {
            long now = clock.getAsLong();
            if (settings.timeoutMillis() > 0 && now - analyticalSince > settings.timeoutMillis() * 1_000_000L) {
                budgetExceeded.incrementAndGet();
                throw new AxonError(QosCodes.BUDGET_EXCEEDED,
                    Messages.get("qos_budget", settings.timeoutMillis()));
            }
            long executed = now - stride.mark;
            stride.mark = now;
            if (!transactionalActive()) {
                return;
            }
            long wait = budget.charge(executed);
            if (wait > 0) {
                pause.accept(wait);
                stride.mark = clock.getAsLong();
                throttleNanos.add(stride.mark - now);
            }
        }

        @Override
        public synchronized void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            long now = clock.getAsLong();
            if (analytical) {
                analyticalRunning.decrementAndGet();
                analyticalLatency.record(now - startedNanos);
            } else {
                transactionalRunning.decrementAndGet();
                transactionalLatency.record(now - startedNanos);
                lastTransactionalEnd = now;
                sawTransactional = true;
            }
            if (holdsSlot) {
                slots.release();
            }
            Stride stride = STRIDES.get();
            if (stride.owner == this) {
                stride.owner = null;
            }
            handle.pacer(null);
            INSIDE.remove();
            if (attached) {
                QueryContext.detach(previous);
            }
        }
    }
}
