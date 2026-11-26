package com.axonbase.core.cluster;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongSupplier;

import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend.SyncMode;
import com.axonbase.core.storage.VersionedKvBackend;

/**
 * Estado durável do participante Raft e aplicação ordenada de entradas confirmadas.
 *
 * <p>Toda aplicação passa por {@link RaftApplier}, inclusive a releitura do log no
 * arranque e a instalação de snapshot. É isso que garante que o plano de controle
 * seja reconstruído tanto num seguidor ao vivo quanto num nó que acabou de subir.</p>
 *
 * <p>O monitor do nó protege o log e o voto. A aplicação ao armazenamento tem trava própria, e o disco é
 * sincronizado fora das duas, o que permite agrupar várias entradas num fsync.</p>
 */
public final class RaftNode implements AutoCloseable {

    private static final long CHECKPOINT_ENTRIES = 4_096;
    /** Lotes guardados para um listener que ainda não foi acoplado (a janela entre subir o nó e o datastore). */
    private static final int PENDING_LIMIT = 10_000;
    private static final String CONFIG_KEY = "__raft/config";

    private final String nodeId;
    private final RaftNodeState state;
    private final FileRaftLog log;
    private final VersionedKvBackend stateMachine;
    private final RaftOptions options;
    private final boolean durable;
    private final GroupSync groupSync;
    private final ReentrantLock applyLock = new ReentrantLock();
    private final Condition appliedAdvanced = applyLock.newCondition();
    private final ReentrantLock notifyLock = new ReentrantLock();
    private final ConcurrentLinkedQueue<AppliedBatchListener.AppliedBatch> notifications =
        new ConcurrentLinkedQueue<>();
    /** Só usado sob {@code notifyLock}. */
    private final ArrayDeque<AppliedBatchListener.AppliedBatch> pending = new ArrayDeque<>();
    private volatile AppliedBatchListener listener = AppliedBatchListener.none();
    private volatile ReplicationHooks hooks = ReplicationHooks.NONE;
    private volatile LongSupplier retentionFloor = () -> Long.MAX_VALUE;
    private volatile boolean learner;
    private volatile long applied;
    private long appliedTerm;
    private long checkpointed;
    private boolean closed;

    public RaftNode(String nodeId, Path dataDir) {
        this(nodeId, dataDir, new MemoryBackend());
    }

    public RaftNode(String nodeId, Path dataDir, VersionedKvBackend stateMachine) {
        this(nodeId, dataDir, stateMachine, RaftOptions.defaults());
    }

    public RaftNode(String nodeId, Path dataDir, VersionedKvBackend stateMachine, RaftOptions options) {
        this.nodeId = nodeId;
        this.options = options;
        this.state = new RaftNodeState(dataDir);
        long floor = options.sync() == SyncMode.OFF ? 0 : state.appliedIndex();
        this.log = new FileRaftLog(dataDir, options.segmentBytes(), floor);
        this.stateMachine = stateMachine;
        this.durable = stateMachine.durable();
        this.groupSync = new GroupSync(options.sync(), log::lastIndex, log::sync);
        recover();
    }

    /**
     * Alinha o índice aplicado, o log e o armazenamento depois de um reinício.
     *
     * <p>Um armazenamento durável diz ele mesmo até onde aplicou ({@link RaftApplier#APPLIED_KEY}), inclusive
     * quando veio de um backup físico. Um volátil é reconstruído relendo o log.</p>
     */
    private void recover() {
        RaftApplier.Applied marker = durable ? RaftApplier.applied(stateMachine).orElse(null) : null;
        if (marker != null) {
            applied = marker.index();
            appliedTerm = marker.term();
            long known = log.termAt(marker.index());
            if (marker.index() < log.baseIndex() || marker.index() > log.lastIndex()
                || known != 0 && known != marker.term()) {
                log.reset(marker.index(), marker.term());
            }
            if (state.term() < marker.term()) {
                state.vote(marker.term(), "");
            }
        } else {
            long persisted = state.appliedIndex();
            if (!durable && log.baseIndex() > 0 && persisted > 0) {
                // O começo do log não existe mais e o estado volátil se perdeu: o líder precisa reenviar tudo.
                log.reset(0, 0);
                state.checkpoint(0, 0, true);
                persisted = 0;
            }
            applied = persisted;
            appliedTerm = state.appliedTerm() != 0 ? state.appliedTerm() : log.termAt(persisted);
            replay(persisted);
            if (log.lastIndex() < persisted) {
                log.reset(persisted, appliedTerm);
            }
        }
        checkpointed = applied;
    }

    private void replay(long through) {
        for (long i = log.firstIndex(); i <= Math.min(through, log.lastIndex()); i++) {
            FileRaftLog.Entry entry = log.entry(i);
            if (entry != null) {
                RaftApplier.applyReplicated(stateMachine, entry.batch(), i, entry.term());
                adoptConfig(entry.batch());
            }
        }
        stateMachine.flush();
    }

    /**
     * Instala o observador de batches aplicados.
     *
     * <p>Com armazenamento volátil, reprocessa o log já aplicado: o {@code Datastore} só existe depois do nó, e
     * sem isso o que foi aplicado antes do acoplamento ficaria invisível para o catálogo em memória. Com
     * armazenamento durável o catálogo é lido do próprio armazenamento, e só são entregues os lotes aplicados
     * desde que o nó subiu.</p>
     */
    public void listener(AppliedBatchListener listener) {
        AppliedBatchListener target = listener == null ? AppliedBatchListener.none() : listener;
        List<AppliedBatchListener.AppliedBatch> replayed = new ArrayList<>();
        // A trava das notificações vem antes e cobre a releitura inteira: um lote aplicado no meio espera por ela
        // para ser entregue depois, em vez de cair numa fila que o acoplamento descarta.
        notifyLock.lock();
        try {
            applyLock.lock();
            try {
                if (!durable) {
                    for (long i = log.firstIndex(); i <= Math.min(applied, log.lastIndex()); i++) {
                        FileRaftLog.Entry entry = log.entry(i);
                        if (entry != null) {
                            // Reaplicar é seguro: o batch é idempotente e o storage já tem os bytes.
                            replayed.add(RaftApplier.applyReplicated(stateMachine, entry.batch(), i, entry.term()));
                        }
                    }
                    // Depois de um snapshot ou de uma compactação o log não conta a história toda: o estado atual
                    // fecha a releitura.
                    if (log.baseIndex() > 0) {
                        replayed.add(currentState());
                    }
                    stateMachine.flush();
                    notifications.clear();
                }
            } finally {
                applyLock.unlock();
            }
            this.listener = target;
            if (durable) {
                replayed.addAll(pending);
            }
            pending.clear();
            replayed.forEach(this::deliver);
        } finally {
            notifyLock.unlock();
        }
        drainNotifications();
    }

    /** O armazenamento inteiro como um lote que o substitui. Só sob {@code applyLock}. */
    private AppliedBatchListener.AppliedBatch currentState() {
        Map<String, byte[]> values = new LinkedHashMap<>();
        for (String key : stateMachine.keysWithPrefix("")) {
            stateMachine.get(key).ifPresent(value -> values.put(key, value));
        }
        return new AppliedBatchListener.AppliedBatch(
            new CommittedBatch("estado-" + applied, values, java.util.Set.of(), true), Map.of());
    }

    /** Instala os pontos de extensão da replicação e mostra a eles a entrada mais recente do log. */
    public synchronized void hooks(ReplicationHooks hooks) {
        this.hooks = hooks == null ? ReplicationHooks.NONE : hooks;
        FileRaftLog.Entry tail = log.entry(log.lastIndex());
        if (tail != null) {
            this.hooks.recovered(tail.batch());
        }
    }

    /** Um learner não vota. */
    void learner(boolean learner) {
        this.learner = learner;
    }

    /** O líder informa aqui o índice mais atrasado que ainda pretende enviar pelo log. */
    void retentionFloor(LongSupplier floor) {
        this.retentionFloor = floor;
    }

    public RaftWire.Response handle(RaftWire.Request request) {
        return handleAll(List.of(request)).getFirst();
    }

    /**
     * Atende requisições que chegaram juntas: grava todas, faz no máximo um fsync, aplica o que foi
     * confirmado e só então responde.
     */
    List<RaftWire.Response> handleAll(List<RaftWire.Request> requests) {
        List<Received> received = new ArrayList<>(requests.size());
        long toSync = 0;
        long commit = 0;
        for (RaftWire.Request request : requests) {
            Received one;
            synchronized (this) {
                one = receive(request);
            }
            received.add(one);
            toSync = Math.max(toSync, one.durableIndex);
            commit = Math.max(commit, one.commit);
        }
        boolean synced = toSync == 0 || groupSync.awaitDurable(toSync);
        if (commit > applied) {
            applyThrough(commit);
        }
        drainNotifications();
        List<RaftWire.Response> responses = new ArrayList<>(received.size());
        synchronized (this) {
            for (Received one : received) {
                boolean accepted = one.accepted && (synced || one.durableIndex == 0);
                long match = one.matchIndex < 0 ? log.lastIndex() : one.matchIndex;
                // Truncada durante o fsync: só o que já foi aplicado é certo.
                responses.add(new RaftWire.Response(state.term(), accepted,
                    accepted || !one.accepted ? match : applied));
            }
        }
        return responses;
    }

    /**
     * @param matchIndex   o que responder como índice conferido; -1 para o último do log (protocolo antigo)
     * @param durableIndex entrada que precisa estar durável antes da resposta, ou 0
     * @param commit       até onde aplicar, ou 0
     */
    private record Received(boolean accepted, long matchIndex, long durableIndex, long commit) {
        static Received rejected() {
            return new Received(false, -1, 0, 0);
        }

        static Received rejected(long hint) {
            return new Received(false, hint, 0, 0);
        }
    }

    private Received receive(RaftWire.Request request) {
        if (request.type() == RaftWire.HELLO) {
            return new Received(true, -1, 0, 0);
        }
        if (request.term() < state.term()) {
            return Received.rejected();
        }
        return switch (request.type()) {
            case RaftWire.VOTE -> vote(request);
            case RaftWire.APPEND -> {
                follow(request);
                yield legacyAppend(request);
            }
            case RaftWire.APPEND_STREAM -> {
                follow(request);
                yield streamAppend(request);
            }
            case RaftWire.SNAPSHOT -> {
                follow(request);
                yield snapshot(request);
            }
            default -> Received.rejected();
        };
    }

    private void follow(RaftWire.Request request) {
        if (request.term() > state.term() || state.votedFor().isEmpty()) {
            state.vote(request.term(), request.leaderOrCandidate());
        }
    }

    private Received vote(RaftWire.Request request) {
        if (learner || groupSync.failed()) {
            return Received.rejected();
        }
        if (request.term() > state.term()) {
            state.vote(request.term(), "");
        }
        RaftPayload.Vote candidate = RaftPayload.decodeVote(request.payload());
        long localTerm = log.termAt(log.lastIndex());
        boolean upToDate = candidate.lastTerm() > localTerm
            || candidate.lastTerm() == localTerm && candidate.lastIndex() >= log.lastIndex();
        boolean free = state.votedFor().isEmpty() || state.votedFor().equals(request.leaderOrCandidate());
        if (free && upToDate) {
            state.vote(request.term(), request.leaderOrCandidate());
        }
        return new Received(free && upToDate, -1, 0, 0);
    }

    private Received streamAppend(RaftWire.Request request) {
        RaftStream.Batch batch = RaftStream.decode(request.payload());
        long previous = request.index();
        if (previous > log.lastIndex()) {
            return Received.rejected(log.lastIndex());
        }
        // Até o índice aplicado tudo é confirmado, e entradas confirmadas são iguais em qualquer líder.
        if (previous > applied && log.termAt(previous) != batch.previousTerm()) {
            return Received.rejected(beforeTermOf(previous));
        }
        long index = previous;
        for (RaftStream.Entry entry : batch.entries()) {
            index++;
            if (index <= applied) {
                continue;
            }
            if (index <= log.lastIndex()) {
                if (log.termAt(index) == entry.term()) {
                    continue;
                }
                truncate(index);
            }
            if (hooks != ReplicationHooks.NONE && !hooks.accept(RaftPayload.decode(entry.payload()))) {
                return Received.rejected(index - 1);
            }
            log.appendEncoded(index, entry.term(), entry.payload());
        }
        return new Received(true, index, index, Math.min(request.commitIndex(), index));
    }

    /** Último índice antes do termo em conflito, para o líder recuar de um termo em vez de uma entrada. */
    private long beforeTermOf(long index) {
        long conflicting = log.termAt(index);
        long candidate = index - 1;
        while (candidate > applied && log.termAt(candidate) == conflicting) {
            candidate--;
        }
        return Math.max(applied, candidate);
    }

    /** Protocolo antigo: uma entrada por requisição, gravada com o termo da requisição. */
    private Received legacyAppend(RaftWire.Request request) {
        if (request.payload().length == 0) {
            return new Received(true, -1, 0, checkedCommit(request));
        }
        RaftPayload.Append append;
        boolean legacy = false;
        try {
            append = RaftPayload.decodeAppend(request.payload());
        } catch (IllegalArgumentException ignored) {
            legacy = true;
            append = new RaftPayload.Append(request.index() - 1, log.termAt(request.index() - 1),
                RaftPayload.decode(request.payload()));
        }
        long previous = append.previousIndex();
        if (previous != 0 && previous > applied && log.termAt(previous) != append.previousTerm()
            && !storedFromThisLeader(previous, append.previousTerm(), request.term())) {
            return Received.rejected();
        }
        long index = request.index();
        if (index > applied) {
            // O líder antigo apaga uma escrita sem quórum e grava outra no mesmo índice e mandato: vale o conteúdo.
            if (index <= log.lastIndex()
                && (log.termAt(index) != request.term() || !sameContent(index, append.batch()))) {
                truncate(index);
            }
            if (index > log.lastIndex()) {
                boolean gap = index != log.lastIndex() + 1;
                if (gap && (!legacy || log.lastIndex() > log.baseIndex())) {
                    return Received.rejected();
                }
                if (!hooks.accept(append.batch())) {
                    return Received.rejected();
                }
                log.append(new FileRaftLog.Entry(index, request.term(), append.batch()));
            }
        }
        // Só se aplica até a entrada que este pedido conferiu: o que vem depois dela ainda não foi comparado.
        long checked = Math.min(index, log.lastIndex());
        return new Received(true, -1, checked, Math.min(request.commitIndex(), checked));
    }

    /**
     * O batimento antigo traz o índice confirmado sem entrada anterior para conferir. Aplica-se só até a última
     * entrada gravada com o mandato deste líder: ela veio dele, e com ela o que está antes. Uma cauda de outro
     * mandato pode ser uma escrita que nunca se confirmou.
     */
    private long checkedCommit(RaftWire.Request request) {
        long index = Math.min(request.commitIndex(), log.lastIndex());
        while (index > applied && log.termAt(index) != request.term()) {
            index--;
        }
        return index;
    }

    /**
     * O protocolo antigo grava cada entrada com o termo do pedido. Uma entrada reenviada de um mandato anterior fica
     * aqui com o termo do líder atual, e ele, na entrada seguinte, informa o termo verdadeiro: sem esta exceção a
     * conferência falharia para sempre. Só um líder há por mandato, então uma entrada gravada com o termo dele veio
     * dele; e ele nunca reescreve uma entrada de mandato anterior.
     *
     * <p>O termo gravado não muda: rebaixá-lo faria este nó parecer mais atrasado que os membros antigos e entregar
     * a eleição a um deles com menos dados.</p>
     */
    private boolean storedFromThisLeader(long index, long claimed, long leaderTerm) {
        return claimed < leaderTerm && index <= log.lastIndex() && log.termAt(index) == leaderTerm;
    }

    /** O lote no índice é o mesmo do pedido, sem olhar o carimbo, que um nó antigo no caminho não repassa. */
    private boolean sameContent(long index, CommittedBatch incoming) {
        FileRaftLog.Entry stored = log.entry(index);
        if (stored == null) {
            return true;
        }
        CommittedBatch batch = stored.batch();
        return batch.transactionId().equals(incoming.transactionId())
            && batch.fullSnapshot() == incoming.fullSnapshot() && batch.deletes().equals(incoming.deletes())
            && batch.puts().keySet().equals(incoming.puts().keySet())
            && batch.puts().entrySet().stream()
                .allMatch(put -> java.util.Arrays.equals(put.getValue(), incoming.puts().get(put.getKey())));
    }

    private Received snapshot(RaftWire.Request request) {
        // O payload do snapshot é um batch atômico do state machine no índice pedido.
        long index = request.index();
        if (index > applied) {
            CommittedBatch batch = RaftPayload.decode(request.payload());
            // O líder anterior à fase E2 manda o estado já aplicado com o índice do fim do log dele. Num nó com
            // dados isso marcaria como aplicadas entradas que não vieram; recusado, o líder segue entrada a entrada.
            if (!describesIndex(batch, index) && (applied > 0 || log.lastIndex() > 0)) {
                return Received.rejected(applied);
            }
            if (!hooks.accept(batch)) {
                return Received.rejected(applied);
            }
            long term = RaftApplier.decodeMarker(batch.puts().get(RaftApplier.APPLIED_KEY))
                .map(RaftApplier.Applied::term).orElse(0L);
            applyLock.lock();
            try {
                notifications.add(RaftApplier.applyReplicated(stateMachine, batch, index, term));
                stateMachine.syncDurable();
                if (index > log.lastIndex() || term != 0 && log.termAt(index) != term) {
                    log.reset(index, term);
                    groupSync.truncated(index + 1);
                }
                applied = index;
                appliedTerm = term;
                checkpointed = index;
                state.checkpoint(index, term, true);
                adoptConfig(batch);
                appliedAdvanced.signalAll();
            } finally {
                applyLock.unlock();
            }
        }
        return new Received(true, -1, 0, 0);
    }

    /** O snapshot diz, no próprio estado, que ele é o daquele índice (o formato desde a fase E2). */
    private static boolean describesIndex(CommittedBatch snapshot, long index) {
        return RaftApplier.decodeMarker(snapshot.puts().get(RaftApplier.APPLIED_KEY))
            .map(marker -> marker.index() == index).orElse(false);
    }

    private void truncate(long index) {
        if (index <= applied) {
            throw new IllegalStateException("tentativa de truncar a entrada confirmada " + index);
        }
        log.truncateFrom(index);
        groupSync.truncated(index);
    }

    // ---- caminho do líder ----

    /**
     * Acrescenta um lote ao log do líder, sem esperar disco.
     *
     * @return o índice atribuído, ou -1 se o termo já não é {@code term}
     */
    synchronized long propose(long term, CommittedBatch batch) {
        if (state.term() != term) {
            return -1;
        }
        long index = log.lastIndex() + 1;
        log.append(new FileRaftLog.Entry(index, term, hooks.stamp(batch)));
        return index;
    }

    synchronized void appendLocal(long term, long index, CommittedBatch batch) {
        if (log.entry(index) == null) {
            log.append(new FileRaftLog.Entry(index, term, batch));
        }
    }

    boolean awaitDurable(long index) {
        return groupSync.awaitDurable(index);
    }

    long durableIndex() {
        return groupSync.durableIndex();
    }

    boolean diskFailed() {
        return groupSync.failed();
    }

    void periodicSync() {
        groupSync.periodicSync();
    }

    long syncCount() {
        return groupSync.syncCount();
    }

    /** Descarta uma cauda local que não chegou a ser confirmada pelo quórum. */
    synchronized void discardUncommittedFrom(long index) {
        if (index > applied) {
            truncate(index);
        }
    }

    void commitLocal(long term, String leader, long index) {
        applyThrough(index);
        drainNotifications();
    }

    // ---- aplicação ----

    /** Aplica, em ordem, as entradas confirmadas até {@code target}. Não notifica o listener. */
    void applyThrough(long target) {
        applyLock.lock();
        try {
            long limit = Math.min(target, log.lastIndex());
            if (limit <= applied) {
                return;
            }
            for (long i = applied + 1; i <= limit; i++) {
                FileRaftLog.Entry entry = log.entry(i);
                if (entry != null) {
                    notifications.add(
                        RaftApplier.applyReplicated(stateMachine, entry.batch(), i, entry.term()));
                    appliedTerm = entry.term();
                    adoptConfig(entry.batch());
                    hooks.committed(i, entry.term(), entry.batch());
                }
                applied = i;
            }
            stateMachine.flush();
            if (!durable) {
                state.checkpoint(applied, appliedTerm, false);
            } else if (applied - checkpointed >= CHECKPOINT_ENTRIES) {
                checkpoint();
            }
            appliedAdvanced.signalAll();
            compact();
        } finally {
            applyLock.unlock();
        }
    }

    /** Espera o índice aplicado chegar a {@code index}; {@code false} se o prazo acabar. */
    boolean awaitApplied(long index, long timeoutMillis) {
        if (applied >= index) {
            return true;
        }
        long remaining = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        applyLock.lock();
        try {
            while (applied < index) {
                if (remaining <= 0) {
                    return false;
                }
                remaining = appliedAdvanced.awaitNanos(remaining);
            }
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            applyLock.unlock();
        }
    }

    /**
     * Leva ao disco o log e o armazenamento e registra o índice. Depois disso as entradas aplicadas podem sair
     * do log, e corrupção abaixo desse ponto é tratada como perda de dado confirmado.
     */
    private void checkpoint() {
        boolean sync = options.sync() != SyncMode.OFF;
        if (sync) {
            log.sync();
            stateMachine.syncDurable();
        }
        state.checkpoint(applied, appliedTerm, true);
        checkpointed = applied;
    }

    /** Descarta o começo do log já aplicado, mantendo a folga configurada. Só com armazenamento durável. */
    private void compact() {
        if (!durable) {
            return;
        }
        long retain = options.retainEntries();
        long target = Math.min(applied - retain, retentionFloor.getAsLong());
        if (options.retainMillis() > 0 && target > log.baseIndex()) {
            target = Math.min(target,
                log.lastIndexWrittenBefore(System.currentTimeMillis() - options.retainMillis()));
        }
        if (target - log.baseIndex() < Math.max(1, retain / 4)) {
            return;
        }
        checkpoint();
        log.compact(target);
    }

    /**
     * Entrega ao listener, na ordem de aplicação, o que ainda não foi entregue. Na volta, tudo que estava
     * aplicado quando foi chamado já foi entregue, ainda que por outra thread.
     */
    void drainNotifications() {
        // Vazia e destravada: ninguém está no meio de uma entrega.
        if (notifications.isEmpty() && !notifyLock.isLocked()) {
            return;
        }
        notifyLock.lock();
        try {
            AppliedBatchListener.AppliedBatch next;
            while ((next = notifications.poll()) != null) {
                if (listener == AppliedBatchListener.NONE) {
                    pending.add(next);
                    if (pending.size() > PENDING_LIMIT) {
                        pending.removeFirst();
                    }
                } else {
                    deliver(next);
                }
            }
        } finally {
            notifyLock.unlock();
        }
    }

    private void deliver(AppliedBatchListener.AppliedBatch batch) {
        try {
            listener.onApplied(batch);
        } catch (RuntimeException failure) {
            // O dado já está aplicado; um listener que falha não pode parar a replicação.
            System.getLogger(RaftNode.class.getName()).log(System.Logger.Level.ERROR,
                "listener falhou no lote " + batch.batch().transactionId(), failure);
        }
    }

    private void adoptConfig(CommittedBatch batch) {
        byte[] configData = batch.puts().get(CONFIG_KEY);
        if (configData != null) {
            state.updatePeers(RaftNodeState.parsePeers(new String(configData, StandardCharsets.UTF_8)));
        }
    }

    // ---- snapshot ----

    /** Retrato do armazenamento para um seguidor que o log já não alcança. */
    record Image(long index, CommittedBatch batch) {
    }

    Image image() {
        Map<String, byte[]> values = new LinkedHashMap<>();
        if (durable) {
            // Visão de um instante: o marcador lido nela diz de que ponto do log os dados são.
            try {
                stateMachine.snapshotScan(0, (key, value, version) -> {
                    values.put(key, value);
                    return true;
                }, null);
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
        applyLock.lock();
        try {
            if (!durable) {
                for (String key : stateMachine.keysWithPrefix("")) {
                    stateMachine.get(key).ifPresent(value -> values.put(key, value));
                }
                values.put(RaftApplier.APPLIED_KEY, RaftApplier.marker(applied, appliedTerm));
            }
        } finally {
            applyLock.unlock();
        }
        long index = RaftApplier.decodeMarker(values.get(RaftApplier.APPLIED_KEY))
            .map(RaftApplier.Applied::index).orElse(applied);
        return new Image(index,
            hooks.stamp(new CommittedBatch("snapshot-" + index, values, java.util.Set.of(), true)));
    }

    // ---- consultas ----

    public synchronized long term() {
        return state.term();
    }

    /** O mandato sem esperar o monitor do nó, que um fsync do log pode segurar. */
    long currentTerm() {
        return state.currentTerm();
    }

    public long lastIndex() {
        return log.lastIndex();
    }

    public synchronized long lastTerm() {
        return log.termAt(log.lastIndex());
    }

    /** Último índice aplicado ao armazenamento. */
    public long commitIndex() {
        return applied;
    }

    /** Primeiro índice ainda no log. */
    long firstLogIndex() {
        return log.firstIndex();
    }

    long baseIndex() {
        return log.baseIndex();
    }

    long lastTermAt(long index) {
        return log.termAt(index);
    }

    /** Bytes do lote no log, ou {@code null} se a entrada não está disponível. */
    byte[] encoded(long index) {
        return log.encoded(index);
    }

    FileRaftLog.Entry entry(long index) {
        return log.entry(index);
    }

    synchronized long beginElection(String candidate) {
        long term = state.term() + 1;
        state.vote(term, candidate);
        return term;
    }

    /** Adota um termo maior visto numa resposta. */
    synchronized void observeTerm(long term) {
        if (term > state.term()) {
            state.vote(term, "");
        }
    }

    public String nodeId() {
        return nodeId;
    }

    public VersionedKvBackend stateMachine() {
        return stateMachine;
    }

    public synchronized List<InetSocketAddress> peers() {
        return state.peers();
    }

    public synchronized void updatePeers(List<InetSocketAddress> persisted) {
        state.updatePeers(persisted);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
            applyLock.lock();
            try {
                checkpoint();
                log.close();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            } finally {
                applyLock.unlock();
            }
        }
    }
}
