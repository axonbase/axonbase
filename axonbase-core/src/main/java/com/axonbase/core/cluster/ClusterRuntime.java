package com.axonbase.core.cluster;

import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend.SyncMode;
import com.axonbase.core.storage.VersionConflictException;
import com.axonbase.core.storage.VersionedKvBackend;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runtime Raft sobre TCP: eleição, replicação em pipeline por seguidor, group commit, leitura em seguidores e
 * learners.
 *
 * <p>Também é a fonte de verdade dos endpoints {@code /ready} e {@code /status}:
 * implementa {@link ClusterStatusProvider} com o papel, o termo, o índice de
 * commit e os membros realmente alcançados.</p>
 */
public final class ClusterRuntime implements AutoCloseable, ClusterStatusProvider, CommitCoordinator {

    private static final long TICK_MILLIS = 25;
    /** Um peer conta como ativo no status se respondeu dentro desta janela. */
    private static final long LIVENESS_NANOS = TimeUnit.MILLISECONDS.toNanos(500);
    /** Sem resposta da maioria por este tempo, uma escrita nova é recusada e a pendente desiste. */
    private static final long QUORUM_LOST_NANOS = TimeUnit.MILLISECONDS.toNanos(2_000);
    private static final long LEARNER_JOIN_NANOS = TimeUnit.MILLISECONDS.toNanos(300);
    private static final long LEARNER_EXPIRY_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long LEADER_FORGOTTEN_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long READ_TIMEOUT_MILLIS = 5_000;
    private static final long BOUNDED_WAIT_MILLIS = 1_000;
    private static final long BATCH_SYNC_MILLIS = 1_000;
    private static final int VOTE_TIMEOUT_MILLIS = 250;
    /** Folga máxima de log guardada para um seguidor atrasado, em múltiplos da retenção. */
    private static final long LAGGARD_RETENTION_FACTOR = 10;

    private final ClusterConfig config;
    private final RaftOptions options;
    private final RaftNode node;
    private final TcpRaftServer server;
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, daemon("raft-timer"));
    private final ExecutorService elections = Executors.newCachedThreadPool(daemon("raft-election"));
    private final ExecutorService notifier = Executors.newSingleThreadExecutor(daemon("raft-notify"));
    private final Object stateLock = new Object();
    private final Object ackSignal = new Object();
    private final Object commitSignal = new Object();
    private final Map<String, InetSocketAddress> addressByNode = new ConcurrentHashMap<>();
    /** Membros que votam, sem o próprio nó: fonte de verdade de members() e quorum(). */
    private final CopyOnWriteArrayList<InetSocketAddress> peers = new CopyOnWriteArrayList<>();
    /** Replicadores do mandato atual; vazio quando o nó não é líder. */
    private final Map<InetSocketAddress, PeerReplicator> replicators = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, Learner> learners = new ConcurrentHashMap<>();
    /** Replicadores parados que ainda podem estar lendo o nó; {@link #close()} espera por eles. */
    private final Set<PeerReplicator> retired = ConcurrentHashMap.newKeySet();
    private final ConcurrentSkipListMap<Long, CompletableFuture<Void>> pendingWrites = new ConcurrentSkipListMap<>();
    private final ReadIndexBatcher readIndexes = new ReadIndexBatcher(this::askLeaderForReadIndex);
    private final AtomicLong electionsStarted = new AtomicLong();
    private final AtomicLong quorumUnavailable = new AtomicLong();
    private final AtomicLong snapshotBytes = new AtomicLong();
    private final AtomicInteger handling = new AtomicInteger();
    private final AtomicBoolean electing = new AtomicBoolean();
    private final Thread applier;
    private volatile ClusterRole role;
    private volatile String leader = "";
    private volatile long lastContact = System.nanoTime();
    private volatile long electionTimeoutMillis;
    private volatile boolean closed;
    /** Mandato em que este nó lidera; 0 fora dele. */
    private volatile long leaderTerm;
    private volatile long commit;
    private volatile long termStart;
    /** Escritas novas esperam este índice ser aplicado: abaixo dele há escritas sem desfecho. */
    private volatile long unresolved;
    /** Muda a cada troca de liderança ou escrita abandonada; invalida validações feitas antes. */
    private volatile long validationEpoch;
    private volatile boolean caughtUp;
    private volatile long caughtUpNanos;
    private long lastJoinNanos = System.nanoTime() - LEARNER_JOIN_NANOS;

    private record Learner(String node, long lastJoinNanos) {
    }

    public ClusterRuntime(ClusterConfig config, Path dataDir) throws IOException {
        this(config, dataDir, new MemoryBackend());
    }

    public ClusterRuntime(ClusterConfig config, Path dataDir, VersionedKvBackend backend) throws IOException {
        this(config, dataDir, backend, RaftOptions.defaults());
    }

    public ClusterRuntime(ClusterConfig config, Path dataDir, VersionedKvBackend backend, RaftOptions options)
            throws IOException {
        this.config = config;
        this.options = options;
        this.role = config.learner() ? ClusterRole.LEARNER : ClusterRole.FOLLOWER;
        this.electionTimeoutMillis = nextElectionTimeout();
        this.node = new RaftNode(config.nodeId(), dataDir, backend, options);
        node.learner(config.learner());
        node.retentionFloor(this::retentionFloor);
        this.addressByNode.put(config.nodeId(), config.advertiseAddress());
        peers.addAll(config.learner() || node.peers().isEmpty() ? config.peers() : node.peers());
        this.server = new TcpRaftServer(config.advertiseAddress().getPort(), new TcpRaftServer.Handler() {
            @Override
            public RaftWire.Response handle(RaftWire.Request request) {
                return handleAll(List.of(request)).getFirst();
            }

            @Override
            public List<RaftWire.Response> handleAll(List<RaftWire.Request> requests) {
                return ClusterRuntime.this.handleAll(requests);
            }
        });
        this.applier = daemon("raft-apply").newThread(this::applyLoop);
        applier.start();
        scheduler.scheduleWithFixedDelay(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
        if (options.sync() == SyncMode.BATCH) {
            scheduler.scheduleWithFixedDelay(this::syncLog, BATCH_SYNC_MILLIS, BATCH_SYNC_MILLIS,
                TimeUnit.MILLISECONDS);
        }
    }

    private static ThreadFactory daemon(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    /**
     * Acopla o observador de batches aplicados, normalmente o {@code Datastore}.
     *
     * <p>Chamar isto entrega o que já foi aplicado e ainda não era visível, de modo que o catálogo em memória
     * fique alinhado com o storage mesmo quando o nó subiu antes do datastore.</p>
     */
    public void appliedBatchListener(AppliedBatchListener listener) {
        node.listener(listener);
    }

    /** Instala os pontos de extensão da replicação (carimbo de tempo, journal). */
    public void hooks(ReplicationHooks hooks) {
        node.hooks(hooks);
    }

    // ------------------------------------------------------------------
    // requisições recebidas
    // ------------------------------------------------------------------

    private List<RaftWire.Response> handleAll(List<RaftWire.Request> requests) {
        long received = System.nanoTime();
        List<RaftWire.Request> forNode = new ArrayList<>(requests.size());
        RaftWire.Response[] responses = new RaftWire.Response[requests.size()];
        boolean fromLeader = false;
        handling.incrementAndGet();
        try {
            for (int i = 0; i < requests.size(); i++) {
                RaftWire.Request request = requests.get(i);
                switch (request.type()) {
                    case RaftWire.READ_INDEX -> responses[i] = answerReadIndex();
                    case RaftWire.JOIN -> responses[i] = answerJoin(request);
                    default -> {
                        if (isReplication(request.type()) && request.term() >= node.term()) {
                            acceptLeader(request);
                            fromLeader = true;
                        }
                        forNode.add(request);
                    }
                }
            }
            if (!forNode.isEmpty()) {
                List<RaftWire.Response> answered = node.handleAll(forNode);
                if (role == ClusterRole.LEADER && node.term() > leaderTerm) {
                    stepDown();
                }
                int next = 0;
                for (int i = 0; i < responses.length; i++) {
                    if (responses[i] == null) {
                        responses[i] = answered.get(next++);
                        observe(requests.get(i), responses[i], received);
                    }
                }
            }
            return List.of(responses);
        } finally {
            if (fromLeader) {
                lastContact = System.nanoTime();
            }
            handling.decrementAndGet();
        }
    }

    private static boolean isReplication(byte type) {
        return type == RaftWire.APPEND || type == RaftWire.APPEND_STREAM || type == RaftWire.SNAPSHOT;
    }

    private void acceptLeader(RaftWire.Request request) {
        synchronized (stateLock) {
            if (role == ClusterRole.LEADER) {
                if (request.term() <= leaderTerm) {
                    return;
                }
                stepDown();
            }
            role = config.learner() ? ClusterRole.LEARNER : ClusterRole.FOLLOWER;
            leader = request.leaderOrCandidate();
            lastContact = System.nanoTime();
        }
        // O líder anuncia o próprio endereço: sem isto o seguidor sabe redirecionar
        // o cliente para um nome, mas não para um destino.
        if (!request.advertise().isEmpty()) {
            addressByNode.put(request.leaderOrCandidate(), parse(request.advertise()));
        }
    }

    private void observe(RaftWire.Request request, RaftWire.Response response, long received) {
        if (!response.accepted()) {
            return;
        }
        if (request.type() == RaftWire.VOTE) {
            lastContact = System.nanoTime();
        } else if (isReplication(request.type()) && node.commitIndex() >= request.commitIndex()) {
            // Aplicado tudo que o líder tinha confirmado quando enviou: em dia a partir da chegada.
            caughtUpNanos = received;
            caughtUp = true;
        }
    }

    private static InetSocketAddress parse(String advertise) {
        int split = advertise.lastIndexOf(':');
        return new InetSocketAddress(advertise.substring(0, split),
            Integer.parseInt(advertise.substring(split + 1)));
    }

    // ------------------------------------------------------------------
    // relógio: eleição, learners, sincronização
    // ------------------------------------------------------------------

    private void tick() {
        try {
            long now = System.nanoTime();
            if (role == ClusterRole.LEADER) {
                expireLearners(now);
            } else if (role == ClusterRole.LEARNER) {
                if (now - lastContact > LEADER_FORGOTTEN_NANOS) {
                    leader = "";
                }
                if (now - lastJoinNanos >= LEARNER_JOIN_NANOS) {
                    lastJoinNanos = now;
                    announce();
                }
            } else if (handling.get() == 0
                && now - lastContact > TimeUnit.MILLISECONDS.toNanos(electionTimeoutMillis)
                && electing.compareAndSet(false, true)) {
                elections.execute(() -> {
                    try {
                        elect();
                    } finally {
                        electing.set(false);
                    }
                });
            }
        } catch (Throwable ignored) {
            // Uma rodada perdida é recuperada no próximo tick; o scheduler não morre.
        }
    }

    private void syncLog() {
        try {
            node.periodicSync();
        } catch (RuntimeException failure) {
            System.getLogger(ClusterRuntime.class.getName()).log(System.Logger.Level.ERROR,
                "fsync periódico do log Raft falhou", failure);
            stepDown();
        }
    }

    private void elect() {
        long term;
        long lastIndex;
        long lastTerm;
        synchronized (stateLock) {
            if (closed || role == ClusterRole.LEADER || role == ClusterRole.LEARNER) {
                return;
            }
            electionsStarted.incrementAndGet();
            role = ClusterRole.CANDIDATE;
            leader = "";
            electionTimeoutMillis = nextElectionTimeout();
            lastContact = System.nanoTime();
            term = node.beginElection(config.nodeId());
            lastIndex = node.lastIndex();
            lastTerm = node.lastTerm();
        }
        List<InetSocketAddress> voters = List.copyOf(peers);
        int needed = (voters.size() + 1) / 2 + 1;
        List<InetSocketAddress> granted = new ArrayList<>();
        ExecutorCompletionService<Map.Entry<InetSocketAddress, RaftWire.Response>> replies =
            new ExecutorCompletionService<>(elections);
        for (InetSocketAddress peer : voters) {
            replies.submit(() -> Map.entry(peer, RaftWire.call(peer, new RaftWire.Request(RaftWire.VOTE,
                config.clusterId(), term, config.nodeId(), advertise(), lastIndex, 0,
                RaftPayload.vote(lastIndex, lastTerm)), VOTE_TIMEOUT_MILLIS)));
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(VOTE_TIMEOUT_MILLIS * 2L);
        for (int i = 0; i < voters.size() && granted.size() + 1 < needed; i++) {
            try {
                Future<Map.Entry<InetSocketAddress, RaftWire.Response>> reply =
                    replies.poll(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                if (reply == null) {
                    break;
                }
                RaftWire.Response response = reply.get().getValue();
                if (response.term() > term) {
                    node.observeTerm(response.term());
                    synchronized (stateLock) {
                        role = ClusterRole.FOLLOWER;
                    }
                    return;
                }
                if (response.accepted()) {
                    granted.add(reply.get().getKey());
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException unreachable) {
                // Peer inalcançável apenas não vota.
            }
        }
        becomeLeader(term, granted, needed);
    }

    private void becomeLeader(long term, List<InetSocketAddress> granted, int needed) {
        long barrier;
        synchronized (stateLock) {
            if (closed || role != ClusterRole.CANDIDATE || node.term() != term) {
                return;
            }
            lastContact = System.nanoTime();
            if (granted.size() + 1 < needed) {
                role = ClusterRole.FOLLOWER;
                return;
            }
            long tail = node.lastIndex();
            commit = node.commitIndex();
            leaderTerm = term;
            // Entradas de mandatos anteriores só se confirmam junto com uma deste: um lote vazio as arrasta.
            barrier = tail > commit
                ? node.propose(term, new CommittedBatch("noop-" + term, Map.of(), Set.of()))
                : commit;
            if (barrier < 0) {
                role = ClusterRole.FOLLOWER;
                return;
            }
            termStart = barrier;
            unresolved = barrier;
            validationEpoch++;
            role = ClusterRole.LEADER;
            leader = config.nodeId();
            for (InetSocketAddress peer : peers) {
                PeerReplicator replicator = replicator(peer, true, tail + 1);
                if (granted.contains(peer)) {
                    replicator.contacted();
                }
                replicators.put(peer, replicator);
                replicator.start();
            }
            for (InetSocketAddress address : learners.keySet()) {
                startLearner(address, tail + 1);
            }
        }
        node.awaitDurable(barrier);
        advanceCommit();
    }

    private static long nextElectionTimeout() {
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(350L, 850L);
    }

    private void stepDown() {
        List<PeerReplicator> stopping;
        synchronized (stateLock) {
            if (role == ClusterRole.LEARNER) {
                return;
            }
            boolean led = role == ClusterRole.LEADER;
            role = ClusterRole.FOLLOWER;
            leader = "";
            lastContact = System.nanoTime();
            if (!led) {
                return;
            }
            leaderTerm = 0;
            validationEpoch++;
            caughtUp = false;
            stopping = new ArrayList<>(replicators.values());
            replicators.clear();
            retired.addAll(stopping);
        }
        stopping.forEach(PeerReplicator::stop);
        // As escritas pendentes podem ou não ser confirmadas pelo próximo líder: o desfecho é desconhecido.
        pendingWrites.values().forEach(
            future -> future.completeExceptionally(new QuorumUnavailableException(config.clusterId())));
        pendingWrites.clear();
    }

    // ------------------------------------------------------------------
    // liderança: replicação e commit
    // ------------------------------------------------------------------

    private PeerReplicator replicator(InetSocketAddress peer, boolean voter, long next) {
        long term = leaderTerm;
        return new PeerReplicator(peer, voter, next, new PeerReplicator.Leader() {
            @Override
            public long term() {
                return term;
            }

            @Override
            public long commitIndex() {
                return commit;
            }

            @Override
            public RaftNode node() {
                return node;
            }

            @Override
            public RaftOptions options() {
                return options;
            }

            @Override
            public String group() {
                return config.clusterId();
            }

            @Override
            public String nodeId() {
                return config.nodeId();
            }

            @Override
            public String advertise() {
                return ClusterRuntime.this.advertise();
            }

            @Override
            public void acknowledged(PeerReplicator from) {
                synchronized (ackSignal) {
                    ackSignal.notifyAll();
                }
                if (from.voter() && from.matchIndex() > commit) {
                    advanceCommit();
                }
            }

            @Override
            public void higherTerm(long newer) {
                node.observeTerm(newer);
                stepDown();
            }

            @Override
            public void snapshotSent(long bytes) {
                snapshotBytes.addAndGet(bytes);
            }
        });
    }

    /** Maior índice durável numa maioria; só entradas do mandato atual contam. */
    private void advanceCommit() {
        synchronized (stateLock) {
            if (role != ClusterRole.LEADER) {
                return;
            }
            List<Long> indexes = new ArrayList<>(peers.size() + 1);
            indexes.add(node.durableIndex());
            for (InetSocketAddress peer : peers) {
                PeerReplicator replicator = replicators.get(peer);
                indexes.add(replicator == null ? 0 : replicator.matchIndex());
            }
            indexes.sort(Comparator.reverseOrder());
            long candidate = indexes.get(quorum() - 1);
            if (candidate <= commit || node.lastTermAt(candidate) != leaderTerm) {
                return;
            }
            commit = candidate;
        }
        synchronized (commitSignal) {
            commitSignal.notifyAll();
        }
        replicators.values().forEach(PeerReplicator::wake);
    }

    /** Aplica no líder o que o quórum confirmou e libera quem esperava. */
    private void applyLoop() {
        while (!closed) {
            try {
                synchronized (commitSignal) {
                    while (!closed && !(role == ClusterRole.LEADER && commit > node.commitIndex())) {
                        commitSignal.wait(100);
                    }
                }
                if (closed) {
                    return;
                }
                node.applyThrough(commit);
                var done = pendingWrites.headMap(node.commitIndex(), true);
                for (Map.Entry<Long, CompletableFuture<Void>> entry = done.pollFirstEntry(); entry != null;
                     entry = done.pollFirstEntry()) {
                    entry.getValue().complete(null);
                }
                // Fora desta thread: um listener que espera uma trava de quem aguarda o commit não a trava.
                notifier.execute(node::drainNotifications);
            } catch (InterruptedException interrupted) {
                return;
            } catch (RuntimeException failure) {
                if (!closed) {
                    System.getLogger(ClusterRuntime.class.getName()).log(System.Logger.Level.ERROR,
                        "falha ao aplicar o log Raft", failure);
                    stepDown();
                }
            }
        }
    }

    private long retentionFloor() {
        if (role != ClusterRole.LEADER) {
            return Long.MAX_VALUE;
        }
        long floor = Long.MAX_VALUE;
        for (PeerReplicator replicator : replicators.values()) {
            if (!replicator.needsBackup()) {
                floor = Math.min(floor, replicator.matchIndex());
            }
        }
        // Um membro parado não segura o log para sempre: além disso ele recebe snapshot.
        return Math.max(floor, node.commitIndex() - options.retainEntries() * LAGGARD_RETENTION_FACTOR);
    }

    @Override
    public long beginValidation() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.commitTimeoutMillis());
        while (true) {
            long epoch = validationEpoch;
            if (role != ClusterRole.LEADER) {
                throw notLeader();
            }
            if (node.awaitApplied(unresolved, TICK_MILLIS) && epoch == validationEpoch) {
                return epoch;
            }
            if (System.nanoTime() - deadline > 0) {
                quorumUnavailable.incrementAndGet();
                throw new QuorumUnavailableException(config.clusterId());
            }
        }
    }

    /** Replica um batch do state machine e só retorna depois do commit por maioria. */
    @Override
    public long confirm(String nodeId, CommittedBatch batch) {
        if (role != ClusterRole.LEADER || !config.nodeId().equals(nodeId)) {
            throw notLeader();
        }
        return confirm(nodeId, batch, beginValidation());
    }

    @Override
    public long confirm(String nodeId, CommittedBatch batch, long validation) {
        long index;
        CompletableFuture<Void> applied = new CompletableFuture<>();
        synchronized (stateLock) {
            if (role != ClusterRole.LEADER || !config.nodeId().equals(nodeId)) {
                throw notLeader();
            }
            if (validation != validationEpoch) {
                throw new VersionConflictException("a liderança mudou durante a transação", "");
            }
            if (!quorumReachable()) {
                // Recusa antes de gravar: aqui é certo que a escrita não aconteceu.
                quorumUnavailable.incrementAndGet();
                throw new QuorumUnavailableException(config.clusterId());
            }
            index = node.propose(leaderTerm, batch);
            if (index < 0) {
                stepDown();
                throw notLeader();
            }
            pendingWrites.put(index, applied);
        }
        replicators.values().forEach(PeerReplicator::wake);
        try {
            node.awaitDurable(index);
        } catch (RuntimeException diskFailure) {
            stepDown();
            throw diskFailure;
        }
        advanceCommit();
        awaitCommit(index, applied);
        return index;
    }

    private void awaitCommit(long index, CompletableFuture<Void> applied) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(options.commitTimeoutMillis());
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    applied.get(TICK_MILLIS * 2, TimeUnit.MILLISECONDS);
                    return;
                } catch (TimeoutException waiting) {
                    if (System.nanoTime() - deadline > 0 || !quorumReachable()) {
                        abandon(index, applied);
                        return;
                    }
                } catch (InterruptedException e) {
                    interrupted = true;
                    abandon(index, applied);
                    return;
                } catch (ExecutionException failed) {
                    if (failed.getCause() instanceof RuntimeException cause) {
                        quorumUnavailable.incrementAndGet();
                        throw cause;
                    }
                    throw new IllegalStateException(failed.getCause());
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Desiste de esperar uma escrita já gravada no log. Ela ainda pode ser confirmada quando o quórum voltar,
     * então as próximas escritas esperam o desfecho antes de validar.
     */
    private void abandon(long index, CompletableFuture<Void> applied) {
        synchronized (stateLock) {
            if (applied.isDone() && !applied.isCompletedExceptionally()) {
                return;
            }
            pendingWrites.remove(index);
            unresolved = Math.max(unresolved, index);
            validationEpoch++;
        }
        quorumUnavailable.incrementAndGet();
        throw new QuorumUnavailableException(config.clusterId());
    }

    private boolean quorumReachable() {
        return reachable(QUORUM_LOST_NANOS) >= quorum();
    }

    /** Este nó mais os membros que respondem há menos que a janela. */
    private int reachable(long windowNanos) {
        int alive = 1;
        for (InetSocketAddress peer : peers) {
            PeerReplicator replicator = replicators.get(peer);
            long since = replicator == null ? -1 : replicator.nanosSinceReply();
            if (since >= 0 && since <= windowNanos) {
                alive++;
            }
        }
        return alive;
    }

    @Override
    public boolean appliesTo(KvBackend backend) {
        return backend == node.stateMachine();
    }

    // ------------------------------------------------------------------
    // leituras
    // ------------------------------------------------------------------

    @Override
    public void awaitRead(ReadConsistency consistency) {
        ReadConsistency wanted = consistency != null ? consistency
            : options.maxReadLagMillis() > 0 ? ReadConsistency.maxLag(options.maxReadLagMillis())
            : ReadConsistency.STALE;
        switch (wanted.mode()) {
            case STALE -> {
            }
            case BOUNDED -> awaitFreshness(wanted.maxLagMillis());
            case LINEARIZABLE -> {
                long index = role == ClusterRole.LEADER ? leaderReadIndex()
                    : readIndexes.await(READ_TIMEOUT_MILLIS);
                if (!node.awaitApplied(index, READ_TIMEOUT_MILLIS)) {
                    throw new StaleReadException("este nó não aplicou o índice " + index + " a tempo");
                }
            }
        }
    }

    /**
     * Nanos desde que este nó esteve em dia com o líder, ou -1 se nunca esteve. Medido pela chegada da
     * mensagem do líder, então não inclui o atraso de rede dela.
     */
    private long staleness() {
        if (role == ClusterRole.LEADER) {
            // O líder está em dia desde o envio que a maioria respondeu por último.
            List<Long> ages = new ArrayList<>(peers.size() + 1);
            ages.add(0L);
            for (InetSocketAddress peer : peers) {
                PeerReplicator replicator = replicators.get(peer);
                long since = replicator == null ? -1 : replicator.nanosSinceReply();
                ages.add(since < 0 ? Long.MAX_VALUE : since);
            }
            ages.sort(Comparator.naturalOrder());
            long age = ages.get(quorum() - 1);
            return node.commitIndex() >= termStart && age < Long.MAX_VALUE ? age : -1;
        }
        return caughtUp ? System.nanoTime() - caughtUpNanos : -1;
    }

    private void awaitFreshness(long maxLagMillis) {
        long limit = TimeUnit.MILLISECONDS.toNanos(maxLagMillis);
        long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(Math.min(maxLagMillis, BOUNDED_WAIT_MILLIS));
        while (true) {
            long age = staleness();
            if (age >= 0 && age <= limit) {
                return;
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new StaleReadException(age < 0 ? "este nó ainda não está em dia com o líder"
                    : "este nó está " + TimeUnit.NANOSECONDS.toMillis(age) + " ms atrás do líder; o limite é "
                    + maxLagMillis + " ms");
            }
            pauseBriefly();
        }
    }

    /**
     * ReadIndex no líder: o commit de agora, confirmado como atual por uma maioria que responda a um envio
     * feito depois deste instante.
     */
    private long leaderReadIndex() {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(READ_TIMEOUT_MILLIS);
        long term = leaderTerm;
        if (role != ClusterRole.LEADER || !node.awaitApplied(termStart, READ_TIMEOUT_MILLIS)) {
            throw new StaleReadException("o líder ainda não confirmou o início do mandato");
        }
        long index = commit < termStart ? termStart : commit;
        long asked = System.nanoTime();
        replicators.values().forEach(PeerReplicator::requestHeartbeat);
        synchronized (ackSignal) {
            while (true) {
                if (role != ClusterRole.LEADER || leaderTerm != term) {
                    throw new StaleReadException("a liderança mudou durante a leitura");
                }
                int confirmed = 1;
                for (InetSocketAddress peer : peers) {
                    PeerReplicator replicator = replicators.get(peer);
                    if (replicator != null && replicator.ackedSince(asked)) {
                        confirmed++;
                    }
                }
                if (confirmed >= quorum()) {
                    return index;
                }
                if (System.nanoTime() - deadline > 0) {
                    throw new StaleReadException("sem quórum para confirmar a liderança");
                }
                try {
                    ackSignal.wait(TICK_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new StaleReadException("leitura interrompida");
                }
            }
        }
    }

    private long askLeaderForReadIndex() {
        InetSocketAddress address = addressByNode.get(leader);
        if (leader.isEmpty() || address == null) {
            throw new StaleReadException("nenhum líder conhecido");
        }
        try {
            RaftWire.Response response = RaftWire.call(address, new RaftWire.Request(RaftWire.READ_INDEX,
                config.clusterId(), node.term(), config.nodeId(), advertise(), 0, 0, new byte[0]),
                (int) READ_TIMEOUT_MILLIS);
            if (!response.accepted()) {
                throw new StaleReadException("o nó " + leader + " não confirmou a leitura");
            }
            return response.matchIndex();
        } catch (IOException unreachable) {
            throw new StaleReadException("líder inalcançável: " + unreachable.getMessage());
        }
    }

    private RaftWire.Response answerReadIndex() {
        try {
            if (role == ClusterRole.LEADER) {
                return new RaftWire.Response(node.term(), true, leaderReadIndex());
            }
        } catch (StaleReadException unavailable) {
            // responde sem aceitar
        }
        return new RaftWire.Response(node.term(), false, 0);
    }

    private static void pauseBriefly() {
        try {
            Thread.sleep(5);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new StaleReadException("leitura interrompida");
        }
    }

    // ------------------------------------------------------------------
    // learners
    // ------------------------------------------------------------------

    /** Learner: apresenta-se ao líder conhecido, ou a todos os membros enquanto não o conhece. */
    private void announce() {
        InetSocketAddress known = leader.isEmpty() ? null : addressByNode.get(leader);
        List<InetSocketAddress> targets = known == null ? List.copyOf(peers) : List.of(known);
        for (InetSocketAddress target : targets) {
            Thread.ofVirtual().name("raft-join").start(() -> {
                try {
                    RaftWire.call(target, new RaftWire.Request(RaftWire.JOIN, config.clusterId(), node.term(),
                        config.nodeId(), advertise(), node.lastIndex(), 0, new byte[0]), VOTE_TIMEOUT_MILLIS);
                } catch (IOException unreachable) {
                    // tenta de novo no próximo anúncio
                }
            });
        }
    }

    private RaftWire.Response answerJoin(RaftWire.Request request) {
        synchronized (stateLock) {
            if (role != ClusterRole.LEADER || request.advertise().isEmpty()) {
                return new RaftWire.Response(node.term(), false, 0);
            }
            InetSocketAddress address = parse(request.advertise());
            if (peers.contains(address)) {
                return new RaftWire.Response(node.term(), false, 0);
            }
            learners.put(address, new Learner(request.leaderOrCandidate(), System.nanoTime()));
            PeerReplicator current = replicators.get(address);
            if (current == null) {
                startLearner(address, request.index() + 1);
            } else if (current.needsBackup() && request.index() > node.baseIndex()) {
                // Voltou com um backup mais novo.
                current.stop();
                retired.add(current);
                startLearner(address, request.index() + 1);
            }
            return new RaftWire.Response(node.term(), true, node.baseIndex());
        }
    }

    private void startLearner(InetSocketAddress address, long next) {
        PeerReplicator replicator = replicator(address, false, next);
        replicators.put(address, replicator);
        replicator.start();
    }

    private void expireLearners(long now) {
        retired.removeIf(PeerReplicator::finished);
        for (Map.Entry<InetSocketAddress, Learner> entry : learners.entrySet()) {
            if (now - entry.getValue().lastJoinNanos() > LEARNER_EXPIRY_NANOS
                && learners.remove(entry.getKey(), entry.getValue())) {
                PeerReplicator replicator = replicators.remove(entry.getKey());
                if (replicator != null) {
                    replicator.stop();
                    retired.add(replicator);
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // membros
    // ------------------------------------------------------------------

    /** Erro estruturado com o líder conhecido, para o transporte redirecionar. */
    public NotLeaderException notLeader() {
        return new NotLeaderException(leader, leaderAddress());
    }

    /** Endereço anunciado deste nó, propagado em cada request para os peers. */
    private String advertise() {
        return RaftWire.advertise(config.advertiseAddress());
    }

    /** Endereço {@code host:porta} do líder conhecido, ou vazio numa eleição. */
    public String leaderAddress() {
        InetSocketAddress address = addressByNode.get(leader);
        if (address == null) {
            return "";
        }
        return address.getHostString() + ":" + address.getPort();
    }

    public ClusterConfig config() {
        return config;
    }

    public int port() {
        return server.port();
    }

    public VersionedKvBackend backend() {
        return node.stateMachine();
    }

    public ClusterRole role() {
        return role;
    }

    /** Membros que votam. Um learner não se conta. */
    public int members() {
        return peers.size() + (config.learner() ? 0 : 1);
    }

    public int quorum() {
        return members() / 2 + 1;
    }

    /**
     * Anexa um peer via log Raft (joint consensus). O batch com __raft/config é
     * replicado pelo quórum ATUAL; após confirm, todos os nós aplicam a mudança.
     */
    public boolean addPeer(String nodeId, InetSocketAddress addr) {
        synchronized (stateLock) {
            // Um learner no ar não vota: para virar membro, precisa antes reiniciar sem o modo learner.
            if (peers.contains(addr) || learners.containsKey(addr)) {
                return false;
            }
        }
        List<InetSocketAddress> newList = new ArrayList<>(peers);
        newList.add(addr);
        if (!confirmConfig("config-add-", newList)) {
            return false;
        }
        synchronized (stateLock) {
            if (!peers.contains(addr)) {
                peers.add(addr);
                addressByNode.put(nodeId, addr);
                if (role == ClusterRole.LEADER) {
                    PeerReplicator replicator = replicator(addr, true, node.lastIndex() + 1);
                    // Carência para o membro novo responder antes de contar como perdido.
                    replicator.contacted();
                    replicators.put(addr, replicator);
                    replicator.start();
                }
            }
            return true;
        }
    }

    /**
     * Retira um peer via log Raft. O peer é removido do quórum ANTES de confirmar
     * (joint consensus: quórum exclui o peer removido). O batch com __raft/config
     * é replicado pelo quórum reduzido.
     */
    public boolean removePeer(String addrPort) {
        InetSocketAddress match = null;
        PeerReplicator removed;
        synchronized (stateLock) {
            for (InetSocketAddress peer : peers) {
                if ((peer.getHostString() + ":" + peer.getPort()).equals(addrPort)) {
                    match = peer;
                    break;
                }
            }
            if (match == null) {
                return false;
            }
            peers.remove(match);
            addressByNode.values().remove(match);
            removed = replicators.remove(match);
        }
        if (removed != null) {
            removed.stop();
            retired.add(removed);
        }
        if (confirmConfig("config-remove-", List.copyOf(peers))) {
            return true;
        }
        synchronized (stateLock) {
            if (!peers.contains(match)) {
                peers.add(match);
                addressByNode.put("", match);
                if (role == ClusterRole.LEADER) {
                    PeerReplicator replicator = replicator(match, true, node.lastIndex() + 1);
                    replicators.put(match, replicator);
                    replicator.start();
                }
            }
        }
        return false;
    }

    private boolean confirmConfig(String prefix, List<InetSocketAddress> members) {
        byte[] bytes = RaftNodeState.formatPeers(members).getBytes(StandardCharsets.UTF_8);
        try {
            confirm(config.nodeId(), new CommittedBatch(
                prefix + java.time.Instant.now().toString().replace(":", "-"),
                Map.of("__raft/config", bytes), Set.of()));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------
    // observação
    // ------------------------------------------------------------------

    /** Retrato de um peer para a API de gestão. {@code lastContactMs} é -1 quando nunca respondeu. */
    public record PeerInfo(String node, String address, long lagEntries, long lastContactMs, boolean active) {
    }

    /** Peers conhecidos, com atraso de replicação e último contato. Só o líder tem dados reais. */
    public List<PeerInfo> peerInfos() {
        List<PeerInfo> out = new ArrayList<>();
        for (InetSocketAddress peer : peers) {
            String address = peer.getHostString() + ":" + peer.getPort();
            String name = address;
            for (Map.Entry<String, InetSocketAddress> e : addressByNode.entrySet()) {
                if (e.getValue().equals(peer) && !e.getKey().isEmpty()) {
                    name = e.getKey();
                }
            }
            out.add(peerInfo(name, peer));
        }
        return out;
    }

    /** Learners para os quais o log já não basta: precisam entrar de um backup mais novo. */
    public List<String> learnersNeedingBackup() {
        return learners.entrySet().stream().filter(e -> {
            PeerReplicator replicator = replicators.get(e.getKey());
            return replicator != null && replicator.needsBackup();
        }).map(e -> e.getValue().node()).toList();
    }

    /** Learners que este líder está alimentando. */
    public List<PeerInfo> learnerInfos() {
        return learners.entrySet().stream().map(e -> peerInfo(e.getValue().node(), e.getKey())).toList();
    }

    private PeerInfo peerInfo(String name, InetSocketAddress peer) {
        PeerReplicator replicator = replicators.get(peer);
        long since = replicator == null ? -1 : replicator.nanosSinceReply();
        long lag = Math.max(0, node.lastIndex() - (replicator == null ? 0 : replicator.matchIndex()));
        return new PeerInfo(name, peer.getHostString() + ":" + peer.getPort(), lag,
            since < 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(since), since >= 0 && since <= LIVENESS_NANOS);
    }

    /** Replicación pendiente por peer: {@code lastIndex - matchIndex} do líder. */
    public Map<InetSocketAddress, Long> peerLag() {
        Map<InetSocketAddress, Long> lag = new java.util.LinkedHashMap<>();
        long last = node.lastIndex();
        for (InetSocketAddress peer : peers) {
            PeerReplicator replicator = replicators.get(peer);
            lag.put(peer, Math.max(0, last - (replicator == null ? 0 : replicator.matchIndex())));
        }
        return lag;
    }

    public long electionsStarted() {
        return electionsStarted.get();
    }

    public long quorumUnavailable() {
        return quorumUnavailable.get();
    }

    /**
     * Último índice de commit confirmado pelo quórum e aplicado neste nó.
     */
    public long commitIndex() {
        return node.commitIndex();
    }

    /**
     * Último índice de log local (pode não estar commitado).
     */
    public long lastIndex() {
        return node.lastIndex();
    }

    /** Primeiro índice que este nó ainda guarda no log. */
    public long firstLogIndex() {
        return node.firstLogIndex();
    }

    /** fsyncs do log pedidos por escritas desde que o nó subiu. */
    public long logSyncs() {
        return node.syncCount();
    }

    public long snapshotBytes() {
        return snapshotBytes.get();
    }

    /** Estado observável, na forma que os endpoints do servidor consomem. */
    @Override
    public ClusterStatusProvider.Status status() {
        return new ClusterStatusProvider.Status(config.nodeId(), config.clusterId(), leader,
            leaderAddress(), role, node.term(), node.commitIndex(), active(), members(), quorum());
    }

    /**
     * Membros vivos: o próprio nó mais os peers que responderam recentemente.
     *
     * <p>Um seguidor só conhece a si mesmo, então relata 1. É por isso que a
     * readiness do seguidor olha para a existência de um líder, e não para a
     * contagem de membros.</p>
     */
    private int active() {
        return role == ClusterRole.LEADER ? reachable(LIVENESS_NANOS) : 1;
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        scheduler.shutdownNow();
        stepDown();
        elections.shutdownNow();
        // Um learner não passa por stepDown, e os replicadores precisam parar antes de o armazenamento fechar.
        replicators.values().forEach(PeerReplicator::stop);
        retired.addAll(replicators.values());
        server.close();
        synchronized (commitSignal) {
            commitSignal.notifyAll();
        }
        try {
            applier.join(5_000);
            notifier.shutdown();
            if (!notifier.awaitTermination(5, TimeUnit.SECONDS)) {
                notifier.shutdownNow();
            }
            for (PeerReplicator replicator : retired) {
                replicator.join(5_000);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        node.close();
    }
}
