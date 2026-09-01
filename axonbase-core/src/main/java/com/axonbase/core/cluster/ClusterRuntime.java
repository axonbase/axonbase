package com.axonbase.core.cluster;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.VersionedKvBackend;

/**
 * Runtime Raft sobre TCP: eleição, heartbeats, replicação por seguidor e commit
 * por maioria.
 *
 * <p>Também é a fonte de verdade dos endpoints {@code /ready} e {@code /status}:
 * implementa {@link ClusterStatusProvider} com o papel, o termo, o índice de
 * commit e os membros realmente alcançados no último ciclo de heartbeat.</p>
 */
public final class ClusterRuntime implements AutoCloseable, ClusterStatusProvider, CommitCoordinator {

    private static final long HEARTBEAT_MILLIS = 75;
    /** Um peer conta como ativo se respondeu dentro de três ciclos de heartbeat. */
    private static final long LIVENESS_MILLIS = HEARTBEAT_MILLIS * 3;

    private final ClusterConfig config;
    private final RaftNode node;
    private final TcpRaftServer server;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final ExecutorService rpcExecutor;
    private final Object stateLock = new Object();
    private final Map<InetSocketAddress, Long> nextIndex = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, Long> matchIndex = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, Long> lastReply = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, Object> peerLocks = new ConcurrentHashMap<>();
    private final Map<String, InetSocketAddress> addressByNode = new ConcurrentHashMap<>();
    /** Lista de peers mutável: é a fonte de verdade de members()/quorum() e do heartbeat. */
    private final CopyOnWriteArrayList<InetSocketAddress> peers = new CopyOnWriteArrayList<>();
    private final AtomicLong electionsStarted = new AtomicLong();
    private final AtomicLong quorumUnavailable = new AtomicLong();
    private final AtomicLong snapshotBytes = new AtomicLong();
    private volatile ClusterRole role = ClusterRole.FOLLOWER;
    private volatile String leader = "";
    private volatile long lastContact = System.nanoTime();
    private volatile long electionTimeoutMillis;

    public ClusterRuntime(ClusterConfig config, Path dataDir) throws IOException {
        this(config, dataDir, new MemoryBackend());
    }

    public ClusterRuntime(ClusterConfig config, Path dataDir, VersionedKvBackend backend) throws IOException {
        this.config = config;
        this.electionTimeoutMillis = nextElectionTimeout();
        this.node = new RaftNode(config.nodeId(), dataDir, backend);
        this.server = new TcpRaftServer(config.advertiseAddress().getPort(), this::handle);
        this.addressByNode.put(config.nodeId(), config.advertiseAddress());
        List<InetSocketAddress> seed = node.peers().isEmpty() ? config.peers() : node.peers();
        int peerCount = Math.max(1, seed.size());
        this.rpcExecutor = Executors.newFixedThreadPool(Math.min(4, peerCount + 1));
        for (InetSocketAddress peer : seed) {
            peers.add(peer);
            nextIndex.put(peer, node.lastIndex() + 1);
            matchIndex.put(peer, 0L);
            lastReply.put(peer, 0L);
        }
        scheduler.scheduleWithFixedDelay(this::tick, HEARTBEAT_MILLIS, HEARTBEAT_MILLIS,
            TimeUnit.MILLISECONDS);
    }

    /**
     * Acopla o observador de batches aplicados, normalmente o {@code Datastore}.
     *
     * <p>Chamar isto reprocessa o log já aplicado, de modo que o catálogo em memória
     * fique alinhado com o storage mesmo quando o nó subiu antes do datastore.</p>
     */
    public void appliedBatchListener(AppliedBatchListener listener) {
        node.listener(listener);
    }

    private RaftWire.Response handle(RaftWire.Request request) {
        if (request.type() == RaftWire.APPEND || request.type() == RaftWire.SNAPSHOT) {
            synchronized (stateLock) {
                leader = request.leaderOrCandidate();
                role = ClusterRole.FOLLOWER;
                lastContact = System.nanoTime();
            }
            // O líder anuncia o próprio endereço: sem isto o seguidor sabe redirecionar
            // o cliente para um nome, mas não para um destino.
            if (!request.advertise().isEmpty()) {
                addressByNode.put(request.leaderOrCandidate(), parse(request.advertise()));
            }
        }
        return node.handle(request);
    }

    private static InetSocketAddress parse(String advertise) {
        int split = advertise.lastIndexOf(':');
        return new InetSocketAddress(advertise.substring(0, split),
            Integer.parseInt(advertise.substring(split + 1)));
    }

    private void tick() {
        try {
            if (role == ClusterRole.LEADER) {
                heartbeat();
            } else if (System.nanoTime() - lastContact
                > TimeUnit.MILLISECONDS.toNanos(electionTimeoutMillis)) {
                elect();
            }
        } catch (Throwable ignored) {
            // Uma rodada perdida é recuperada no próximo tick; o scheduler não morre.
        }
    }

    private void elect() {
        electionsStarted.incrementAndGet();
        long term;
        long lastIndex;
        long lastTerm;
        synchronized (stateLock) {
            role = ClusterRole.CANDIDATE;
            leader = "";
            electionTimeoutMillis = nextElectionTimeout();
            term = node.beginElection(config.nodeId());
            lastIndex = node.lastIndex();
            lastTerm = node.lastTerm();
        }
        int votes = 1;
        ExecutorCompletionService<RaftWire.Response> replies = new ExecutorCompletionService<>(rpcExecutor);
        for (InetSocketAddress peer : peers) {
            replies.submit(() -> RaftWire.call(peer, new RaftWire.Request(RaftWire.VOTE,
                config.clusterId(), term, config.nodeId(), advertise(), lastIndex, 0,
                RaftPayload.vote(lastIndex, lastTerm)), 250));
        }
        for (int i = 0; i < peers.size() && votes < quorum(); i++) {
            try {
                RaftWire.Response response = replies.take().get();
                if (response.term() > term) {
                    stepDown();
                    return;
                }
                if (response.accepted()) {
                    votes++;
                }
            } catch (Exception ignored) {
                // Peer inalcançável apenas não vota.
            }
        }
        synchronized (stateLock) {
            if (role != ClusterRole.CANDIDATE || node.term() != term) {
                return;
            }
            lastContact = System.nanoTime();
            if (votes < quorum()) {
                role = ClusterRole.FOLLOWER;
                return;
            }
            role = ClusterRole.LEADER;
            leader = config.nodeId();
            for (InetSocketAddress peer : peers) {
                nextIndex.put(peer, node.lastIndex() + 1);
                matchIndex.put(peer, 0L);
            }
        }
        heartbeat();
    }

    private long nextElectionTimeout() {
        return java.util.concurrent.ThreadLocalRandom.current().nextLong(350L, 850L);
    }

    private void heartbeat() {
        long term;
        long lastIndex;
        long commitIndex;
        synchronized (stateLock) {
            if (role != ClusterRole.LEADER) {
                return;
            }
            term = node.term();
            lastIndex = node.lastIndex();
            commitIndex = node.commitIndex();
        }
        for (InetSocketAddress peer : peers) {
            rpcExecutor.execute(() -> heartbeat(peer, term, lastIndex, commitIndex));
        }
    }

    private void heartbeat(InetSocketAddress peer, long term, long lastIndex, long commitIndex) {
        synchronized (peerLock(peer)) {
            try {
                // Um seguidor recuperado pode ter ficado atrás durante um failover.
                if (nextIndex.getOrDefault(peer, lastIndex + 1) <= lastIndex) {
                    if (!replicate(peer, lastIndex, term)) {
                        installSnapshot(peer);
                    }
                    return;
                }
                RaftWire.Response response = RaftWire.call(peer, new RaftWire.Request(RaftWire.APPEND,
                    config.clusterId(), term, config.nodeId(), advertise(), 0, commitIndex, new byte[0]), 250);
                lastReply.put(peer, System.nanoTime());
                if (response.accepted() && response.matchIndex() < commitIndex) {
                    nextIndex.put(peer, Math.max(1, response.matchIndex() + 1));
                    if (!replicate(peer, lastIndex, term)) {
                        installSnapshot(peer);
                    }
                }
            } catch (IOException ignored) {
                // Peer silencioso deixa de contar como ativo pela janela de liveness.
            }
        }
    }

    /** Replica um batch do state machine e só retorna depois do commit por maioria. */
    @Override
    public long confirm(String nodeId, CommittedBatch batch) {
        long index;
        long term;
        synchronized (stateLock) {
            if (role != ClusterRole.LEADER || !config.nodeId().equals(nodeId)) {
                throw notLeader();
            }
            index = node.lastIndex() + 1;
            term = node.term();
            node.appendLocal(term, index, batch);
        }
        int acknowledged = 1;
        ExecutorCompletionService<Boolean> replies = new ExecutorCompletionService<>(rpcExecutor);
        for (InetSocketAddress peer : peers) {
            replies.submit(() -> replicate(peer, index, term));
        }
        for (int i = 0; i < peers.size() && acknowledged < quorum(); i++) {
            try {
                if (replies.take().get()) {
                    acknowledged++;
                }
            } catch (Exception ignored) {
                // Peer inalcançável apenas não confirma o batch.
            }
        }
        if (acknowledged < quorum()) {
            quorumUnavailable.incrementAndGet();
            node.discardUncommittedFrom(index);
            throw new QuorumUnavailableException(config.clusterId());
        }
        long commit;
        synchronized (stateLock) {
            if (role != ClusterRole.LEADER || node.term() != term) {
                node.discardUncommittedFrom(index);
                throw notLeader();
            }
            matchIndex.put(config.advertiseAddress(), index);
            commit = confirmedIndex(term);
        }
        node.commitLocal(term, config.nodeId(), commit);
        scheduler.execute(this::heartbeat);
        return commit;
    }

    /** Maior índice replicado por uma maioria, somente no termo atual. */
    private long confirmedIndex(long term) {
        java.util.List<Long> indexes = new java.util.ArrayList<>(matchIndex.values());
        indexes.add(node.lastIndex());
        indexes.sort(java.util.Comparator.reverseOrder());
        long candidate = indexes.get(quorum() - 1);
        return node.lastTermAt(candidate) == term ? candidate : node.commitIndex();
    }

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

    private boolean replicate(InetSocketAddress peer, long target, long term) {
        synchronized (peerLock(peer)) {
            long next = nextIndex.getOrDefault(peer, target);
            while (next <= target) {
                FileRaftLog.Entry entry = node.entry(next);
                if (entry == null) {
                    return false;
                }
                try {
                    RaftWire.Response response = RaftWire.call(peer, new RaftWire.Request(RaftWire.APPEND,
                        config.clusterId(), term, config.nodeId(), advertise(), next, node.commitIndex(),
                        RaftPayload.append(next - 1, node.lastTermAt(next - 1), entry.batch())), 500);
                    lastReply.put(peer, System.nanoTime());
                    if (response.term() > term) {
                        stepDown();
                        return false;
                    }
                    if (response.accepted()) {
                        matchIndex.put(peer, next);
                        nextIndex.put(peer, next + 1);
                        next++;
                        continue;
                    }
                    if (next == 1) {
                        return false;
                    }
                    next = Math.max(1, next - 1);
                    nextIndex.put(peer, next);
                } catch (IOException unreachable) {
                    return false;
                }
            }
            return matchIndex.getOrDefault(peer, 0L) >= target;
        }
    }

    /** Último recurso para seguidor com log divergente: instala a visão confirmada do líder. */
    private void installSnapshot(InetSocketAddress peer) {
        synchronized (peerLock(peer)) {
            java.util.Map<String, byte[]> values = new java.util.LinkedHashMap<>();
            for (String key : node.stateMachine().keysWithPrefix("")) {
                node.stateMachine().get(key).ifPresent(value -> values.put(key, value));
            }
            try {
                byte[] payload = RaftPayload.encode(new CommittedBatch("snapshot-" + node.lastIndex(),
                    values, java.util.Set.of(), true));
                snapshotBytes.addAndGet(payload.length);
                RaftWire.Response response = RaftWire.call(peer, new RaftWire.Request(RaftWire.SNAPSHOT,
                    config.clusterId(), node.term(), config.nodeId(), advertise(), node.lastIndex(),
                    node.commitIndex(), payload), 500);
                if (response.accepted()) {
                    matchIndex.put(peer, node.lastIndex());
                    nextIndex.put(peer, node.lastIndex() + 1);
                    lastReply.put(peer, System.nanoTime());
                }
            } catch (IOException ignored) {
                // O próximo heartbeat volta a tentar depois que o nó recuperar.
            }
        }
    }

    private Object peerLock(InetSocketAddress peer) {
        return peerLocks.computeIfAbsent(peer, ignored -> new Object());
    }

    private void stepDown() {
        synchronized (stateLock) {
            role = ClusterRole.FOLLOWER;
            leader = "";
        }
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

    public int members() {
        return peers.size() + 1;
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
            if (peers.contains(addr)) {
                return false;
            }
        }
        List<InetSocketAddress> newList = new java.util.ArrayList<>(peers);
        newList.add(addr);
        byte[] configBytes = formatPeers(newList).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            confirm(config.nodeId(), new CommittedBatch("config-add-"
                + java.time.Instant.now().toString().replace(":", "-"),
                java.util.Map.of("__raft/config", configBytes), java.util.Set.of()));
        } catch (RuntimeException e) {
            return false;
        }
        synchronized (stateLock) {
            if (!peers.contains(addr)) {
                peers.add(addr);
                addressByNode.put(nodeId, addr);
                nextIndex.put(addr, node.lastIndex() + 1);
                matchIndex.put(addr, 0L);
                lastReply.put(addr, 0L);
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
        InetSocketAddress match;
        synchronized (stateLock) {
            match = null;
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
            nextIndex.remove(match);
            matchIndex.remove(match);
            lastReply.remove(match);
        }
        List<InetSocketAddress> newList = java.util.List.copyOf(peers);
        byte[] configBytes = formatPeers(newList).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try {
            confirm(config.nodeId(), new CommittedBatch("config-remove-"
                + java.time.Instant.now().toString().replace(":", "-"),
                java.util.Map.of("__raft/config", configBytes), java.util.Set.of()));
        } catch (RuntimeException e) {
            synchronized (stateLock) {
                if (!peers.contains(match)) {
                    peers.add(match);
                    addressByNode.put("", match);
                    nextIndex.put(match, node.lastIndex() + 1);
                    matchIndex.put(match, 0L);
                    lastReply.put(match, 0L);
                }
            }
            return false;
        }
        return true;
    }

    private static String formatPeers(List<InetSocketAddress> peers) {
        StringBuilder sb = new StringBuilder();
        for (InetSocketAddress p : peers) {
            if (sb.length() > 0) sb.append(',');
            sb.append(p.getHostString()).append(':').append(p.getPort());
        }
        return sb.toString();
    }

    private static List<InetSocketAddress> parsePeers(String raw) {
        List<InetSocketAddress> out = new java.util.ArrayList<>();
        if (raw == null || raw.isBlank()) return out;
        for (String part : raw.split(",")) {
            part = part.trim();
            int i = part.lastIndexOf(':');
            if (i < 1) continue;
            try { out.add(new InetSocketAddress(part.substring(0, i), Integer.parseInt(part.substring(i + 1)))); }
            catch (NumberFormatException ignored) {}
        }
        return out;
    }

    /** Replicación pendiente por peer: {@code lastIndex - matchIndex} do líder. */
    public Map<InetSocketAddress, Long> peerLag() {
        Map<InetSocketAddress, Long> lag = new java.util.LinkedHashMap<>();
        long last = node.lastIndex();
        for (InetSocketAddress peer : peers) {
            lag.put(peer, Math.max(0, last - matchIndex.getOrDefault(peer, 0L)));
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
     * Último índice de commit confirmado pelo quórum.
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
        if (role != ClusterRole.LEADER) {
            return 1;
        }
        long now = System.nanoTime();
        long window = TimeUnit.MILLISECONDS.toNanos(LIVENESS_MILLIS);
        int alive = 1;
        for (Long reply : lastReply.values()) {
            if (reply > 0 && now - reply <= window) {
                alive++;
            }
        }
        return alive;
    }

    @Override
    public void close() throws IOException {
        scheduler.shutdownNow();
        rpcExecutor.shutdownNow();
        server.close();
    }
}
