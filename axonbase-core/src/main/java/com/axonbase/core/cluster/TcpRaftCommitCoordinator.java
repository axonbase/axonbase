package com.axonbase.core.cluster;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * Confirma batches por AppendEntries TCP em uma maioria de peers.
 *
 * <p>Quando construído a partir de um {@link ClusterRuntime}, delega tudo a ele e
 * herda o tratamento de líder e de quórum. O construtor com lista de peers existe
 * para exercitar o caminho de wire isoladamente.</p>
 */
public final class TcpRaftCommitCoordinator implements CommitCoordinator {

    private final ClusterRuntime runtime;
    private final String group;
    private final String node;
    private final List<InetSocketAddress> peers;
    private long term = 1;
    private long index;

    public TcpRaftCommitCoordinator(String group, String node, List<InetSocketAddress> peers) {
        this.group = group;
        this.node = node;
        this.peers = List.copyOf(peers);
        this.runtime = null;
    }

    public TcpRaftCommitCoordinator(ClusterRuntime runtime) {
        this.group = runtime.config().clusterId();
        this.node = runtime.config().nodeId();
        this.peers = List.of();
        this.runtime = runtime;
    }

    @Override
    public synchronized long confirm(String nodeId, CommittedBatch batch) {
        if (runtime != null) {
            return runtime.confirm(nodeId, batch);
        }
        if (!node.equals(nodeId)) {
            throw new NotLeaderException(node);
        }
        int accepted = 1;
        long next = ++index;
        List<InetSocketAddress> acceptedPeers = new java.util.ArrayList<>();
        for (InetSocketAddress peer : peers) {
            try {
                RaftWire.Response response = RaftWire.call(peer, new RaftWire.Request(RaftWire.APPEND,
                    group, term, node, next, 0, RaftPayload.append(next - 1, 0, batch)), 1000);
                if (response.accepted()) {
                    accepted++;
                    acceptedPeers.add(peer);
                }
            } catch (Exception unreachable) {
                // Peer fora do ar apenas não conta para o quórum.
            }
        }
        if (accepted < (peers.size() + 1) / 2 + 1) {
            throw new QuorumUnavailableException(group);
        }
        for (InetSocketAddress peer : acceptedPeers) {
            try {
                RaftWire.call(peer, new RaftWire.Request(RaftWire.APPEND, group, term, node, next,
                    next, new byte[0]), 1000);
            } catch (Exception ignored) {
                // O commit já foi decidido; o peer converge no próximo heartbeat.
            }
        }
        return next;
    }
}
