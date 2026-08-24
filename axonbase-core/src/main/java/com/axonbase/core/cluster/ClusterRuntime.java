package com.axonbase.core.cluster;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
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
    private final Map<InetSocketAddress, Long> nextIndex = new LinkedHashMap<>();
    private final Map<InetSocketAddress, Long> matchIndex = new LinkedHashMap<>();
    private final Map<InetSocketAddress, Long> lastReply = new LinkedHashMap<>();
    private final Map<String, InetSocketAddress> addressByNode = new LinkedHashMap<>();
    private volatile ClusterRole role = ClusterRole.FOLLOWER;
    private volatile String leader = "";
    private volatile long lastContact = System.nanoTime();
    private final long electionTimeoutMillis;

    public ClusterRuntime(ClusterConfig config, Path dataDir) throws IOException {
        this(config, dataDir, new MemoryBackend());
    }

    public ClusterRuntime(ClusterConfig config, Path dataDir, VersionedKvBackend backend) throws IOException {
        this.config = config;
        this.electionTimeoutMillis = 350L + Math.floorMod(config.nodeId().hashCode() * 1103515245L, 500);
        this.node = new RaftNode(config.nodeId(), dataDir, backend);
        this.server = new TcpRaftServer(config.advertiseAddress().getPort(), this::handle);
        this.addressByNode.put(config.nodeId(), config.advertiseAddress());
        for (InetSocketAddress peer : config.peers()) {
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
            leader = request.leaderOrCandidate();
            role = ClusterRole.FOLLOWER;
            lastContact = System.nanoTime();
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
        } catch (RuntimeException ignored) {
            // Uma rodada perdida é recuperada no próximo tick; não derruba o scheduler.
        }
    }

    private synchronized void elect() {
        role = ClusterRole.CANDIDATE;
        long term = node.beginElection(config.nodeId());
        int votes = 1;
        for (InetSocketAddress peer : config.peers()) {
            try {
                RaftWire.Response response = RaftWire.call(peer, new RaftWire.Request(RaftWire.VOTE,
                    config.clusterId(), term, config.nodeId(), advertise(), node.lastIndex(), 0,
                    RaftPayload.vote(node.lastIndex(), node.lastTerm())), 250);
                if (response.term() > term) {
                    role = ClusterRole.FOLLOWER;
                    return;
                }
                if (response.accepted()) {
                    votes++;
                    lastReply.put(peer, System.nanoTime());
                }
            } catch (IOException ignored) {
                // Peer inalcançável apenas não vota.
            }
        }
        lastContact = System.nanoTime();
        if (votes >= quorum()) {
            role = ClusterRole.LEADER;
            leader = config.nodeId();
            for (InetSocketAddress peer : config.peers()) {
                nextIndex.put(peer, node.lastIndex() + 1);
                matchIndex.put(peer, 0L);
            }
            heartbeat();
        } else {
            role = ClusterRole.FOLLOWER;
        }
    }

    private synchronized void heartbeat() {
        if (role != ClusterRole.LEADER) {
            return;
        }
        for (InetSocketAddress peer : config.peers()) {
            try {
                // Um seguidor recuperado pode ter ficado atrás durante um failover.
                // Antes do heartbeat vazio, entrega todas as entradas pendentes.
                if (nextIndex.getOrDefault(peer, node.lastIndex() + 1) <= node.lastIndex()) {
                    if (!replicate(peer, node.lastIndex(), node.term())) {
                        installSnapshot(peer);
                    }
                    continue;
                }
                RaftWire.Response response = RaftWire.call(peer, new RaftWire.Request(RaftWire.APPEND, config.clusterId(),
                    node.term(), config.nodeId(), advertise(), 0, node.commitIndex(), new byte[0]), 250);
                lastReply.put(peer, System.nanoTime());
                // Heartbeat também funciona como leitura de progresso. Um nó recém
                // recuperado pode responder, mas ainda estar atrás do commit atual.
                if (response.accepted() && response.matchIndex() < node.commitIndex()) {
                    nextIndex.put(peer, Math.max(1, response.matchIndex() + 1));
                    if (!replicate(peer, node.lastIndex(), node.term())) {
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
    public synchronized long confirm(String nodeId, CommittedBatch batch) {
        if (role != ClusterRole.LEADER || !config.nodeId().equals(nodeId)) {
            throw notLeader();
        }
        long index = node.lastIndex() + 1;
        long term = node.term();
        node.appendLocal(term, index, batch);
        int acknowledged = 1;
        for (InetSocketAddress peer : config.peers()) {
            if (replicate(peer, index, term)) {
                acknowledged++;
            }
        }
        if (acknowledged < quorum()) {
            throw new QuorumUnavailableException(config.clusterId());
        }
        node.commitLocal(term, config.nodeId(), index);
        heartbeat();
        return index;
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
                    role = ClusterRole.FOLLOWER;
                    leader = "";
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

    /** Último recurso para seguidor com log divergente: instala a visão confirmada do líder. */
    private void installSnapshot(InetSocketAddress peer) {
        java.util.Map<String, byte[]> values = new java.util.LinkedHashMap<>();
        for (String key : node.stateMachine().keysWithPrefix("")) {
            node.stateMachine().get(key).ifPresent(value -> values.put(key, value));
        }
        try {
            RaftWire.Response response = RaftWire.call(peer, new RaftWire.Request(RaftWire.SNAPSHOT,
                config.clusterId(), node.term(), config.nodeId(), advertise(), node.lastIndex(),
                node.commitIndex(), RaftPayload.encode(new CommittedBatch("snapshot-" + node.lastIndex(),
                    values, java.util.Set.of()))), 500);
            if (response.accepted()) {
                matchIndex.put(peer, node.lastIndex());
                nextIndex.put(peer, node.lastIndex() + 1);
                lastReply.put(peer, System.nanoTime());
            }
        } catch (IOException ignored) {
            // O próximo heartbeat volta a tentar depois que o nó recuperar.
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
        return config.peers().size() + 1;
    }

    public int quorum() {
        return members() / 2 + 1;
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
        server.close();
    }
}
