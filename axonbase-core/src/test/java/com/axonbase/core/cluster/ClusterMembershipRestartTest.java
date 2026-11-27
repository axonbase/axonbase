package com.axonbase.core.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A lista de membros que um seguidor carrega ao reiniciar, depois de uma entrada ou de uma saída. */
@DisplayName("Raft: membros depois de reiniciar um seguidor")
class ClusterMembershipRestartTest {

    @TempDir
    Path root;

    private static String text(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }

    private static Set<String> peersOf(ClusterRuntime runtime) {
        return runtime.peerInfos().stream().map(ClusterRuntime.PeerInfo::address).collect(Collectors.toSet());
    }

    private static List<ClusterRuntime> followers(ClusterHarness cluster, ClusterRuntime leader) {
        return cluster.nodes().stream().filter(node -> node != leader).toList();
    }

    /**
     * Logo depois da eleição o líder pode só ter ouvido quem lhe deu o voto (com o log vazio, a convergência não prova
     * resposta alguma), e recusa mudar os membros enquanto não alcança o quórum da lista nova.
     */
    private static void awaitLeaderHeardEveryone(ClusterRuntime leader) {
        ClusterHarness.await("o líder não ouviu todos os membros", 10_000,
            () -> leader.peerInfos().stream().allMatch(ClusterRuntime.PeerInfo::active));
    }

    @Test
    @DisplayName("depois de uma saída, o seguidor reiniciado vê o líder e não conta a si mesmo")
    void restartedFollowerAfterLeaveSeesTheLeaderAndNotItself() {
        try (ClusterHarness cluster = ClusterHarness.memory(root, RaftOptions.defaults(), "a", "b", "c")) {
            cluster.startAll();
            ClusterRuntime leader = cluster.awaitLeader();
            cluster.awaitConverged();
            String leaderId = leader.config().nodeId();
            String stays = followers(cluster, leader).get(0).config().nodeId();
            String leaves = followers(cluster, leader).get(1).config().nodeId();

            awaitLeaderHeardEveryone(leader);
            // quem sai para antes: de pé e sem batimentos, abriria uma eleição enquanto a saída é confirmada
            cluster.stop(leaves);
            assertTrue(leader.removePeer(text(cluster.address(leaves))));
            cluster.awaitConverged();
            cluster.stop(stays);
            ClusterRuntime restarted = cluster.start(stays);

            assertEquals(Set.of(text(cluster.address(leaderId))), peersOf(restarted));
            assertEquals(2, restarted.members());
        }
    }

    @Test
    @DisplayName("depois de uma entrada, o seguidor reiniciado vê os outros três")
    void restartedFollowerAfterJoinSeesTheOtherThree() {
        try (ClusterHarness cluster = ClusterHarness.memory(root, RaftOptions.defaults(), "a", "b", "c")) {
            cluster.startAll();
            ClusterRuntime leader = cluster.awaitLeader();
            cluster.awaitConverged();
            String leaderId = leader.config().nodeId();
            String follower = followers(cluster, leader).get(0).config().nodeId();
            String other = followers(cluster, leader).get(1).config().nodeId();
            InetSocketAddress joining = cluster.address("d");

            awaitLeaderHeardEveryone(leader);
            assertTrue(leader.addPeer("d", joining));
            cluster.awaitConverged();
            cluster.stop(follower);
            ClusterRuntime restarted = cluster.start(follower);

            assertEquals(Set.of(text(cluster.address(leaderId)), text(cluster.address(other)), text(joining)),
                peersOf(restarted));
            assertEquals(4, restarted.members());
        }
    }
}
