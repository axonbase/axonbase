package com.axonbase.core.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.core.Session;
import com.axonbase.core.cluster.ClusterConfig;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Programas AxonPL num cluster Raft de 3 nós: definição, chamada, remoção, versão e reinício. */
@DisplayName("Cluster: funcoes, procedures e packages AxonPL")
class ProgramsClusterTest {

    /** Um nó: runtime Raft, backend e datastore ligados entre si. */
    private static final class Node {
        final String id;
        final InetSocketAddress address;
        final List<InetSocketAddress> peers;
        final Path dir;
        ClusterRuntime runtime;
        MemoryBackend backend;
        Datastore ds;

        Node(String id, InetSocketAddress address, List<InetSocketAddress> peers, Path dir) {
            this.id = id;
            this.address = address;
            this.peers = peers;
            this.dir = dir;
        }

        void start() throws java.io.IOException {
            backend = new MemoryBackend();
            runtime = new ClusterRuntime(new ClusterConfig(id, "g", address, peers), dir, backend);
            ds = new Datastore(backend);
            runtime.appliedBatchListener(ds.appliedBatchListener());
            ds.commitCoordinator(runtime, id);
        }

        void stop() {
            try {
                runtime.close();
            } catch (java.io.IOException e) {
                throw new java.io.UncheckedIOException(e);
            }
        }
    }

    private static List<Integer> reservePorts(int count) throws Exception {
        List<ServerSocket> held = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                ServerSocket socket = new ServerSocket(0);
                held.add(socket);
                ports.add(socket.getLocalPort());
            }
        } finally {
            for (ServerSocket socket : held) {
                socket.close();
            }
        }
        return ports;
    }

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    private static Node leader(List<Node> nodes) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            for (Node n : nodes) {
                if (n.runtime.role() == ClusterRole.LEADER && n.runtime.status().ready()) {
                    return n;
                }
            }
            Thread.sleep(75);
        }
        throw new AssertionError("nenhum lider eleito");
    }

    /** Espera a condicao ficar verdadeira (replicacao e assincrona nos seguidores). */
    private static void await(String what, Supplier<Boolean> cond) throws Exception {
        for (int attempt = 0; attempt < 400; attempt++) {
            try {
                if (cond.get()) {
                    return;
                }
            } catch (RuntimeException ignored) {
                // ainda nao replicou
            }
            Thread.sleep(75);
        }
        throw new AssertionError("nao convergiu: " + what);
    }

    private static AxonValue run(Node n, String sql) {
        return n.ds.execute(sql, session(), null);
    }

    private static long version(Node n, String kind, String name) {
        return run(n, "INFO FOR " + kind + " " + name).asObject().get("version").asLong();
    }

    /** Sobe o cluster, repetindo se a porta reservada for tomada por outro processo (intermitencia conhecida). */
    private List<Node> startCluster() throws Exception {
        java.net.BindException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                return startClusterOnce();
            } catch (java.net.BindException e) {
                last = e;
            }
        }
        throw last;
    }

    private List<Node> startClusterOnce() throws Exception {
        List<Integer> ports = reservePorts(3);
        List<InetSocketAddress> addrs = new ArrayList<>();
        for (int p : ports) {
            addrs.add(new InetSocketAddress("127.0.0.1", p));
        }
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            List<InetSocketAddress> peers = new ArrayList<>(addrs);
            peers.remove(i);
            Node n = new Node("n" + (i + 1), addrs.get(i), peers, Files.createTempDirectory("pl-n" + (i + 1)));
            try {
                n.start();
            } catch (java.net.BindException e) {
                nodes.forEach(Node::stop);
                throw e;
            }
            nodes.add(n);
        }
        return nodes;
    }

    @Test
    void definicaoChamadaRemocaoEVersaoReplicam() throws Exception {
        List<Node> nodes = startCluster();
        try {
            Node leader = leader(nodes);
            List<Node> followers = nodes.stream().filter(n -> n != leader).toList();

            run(leader, "CREATE PACKAGE base AS $$\nK = 10\ndef mais(x):\n    return x + K\n$$");
            run(leader, "CREATE FUNCTION dobro(x int) RETURNS int AS $$ return x * 2 $$");
            run(leader, "CREATE PROCEDURE grava(v int) AS $$\n"
                + "    db.exec(\"CREATE item CONTENT {v: $v}\", {\"v\": v})\n$$");

            for (Node f : followers) {
                await("definicoes em " + f.id, () -> run(f, "RETURN dobro(21)").asLong() == 42
                    && run(f, "RETURN base::mais(1)").asLong() == 11
                    && "grava".equals(run(f, "INFO FOR PROCEDURE grava").asObject().get("name").asString()));
            }

            // CALL no lider replica os efeitos (batches KV) para os seguidores
            run(leader, "CALL grava(7)");
            run(leader, "CALL grava(8)");
            for (Node f : followers) {
                await("linhas em " + f.id, () -> run(f, "SELECT * FROM item").asArray().size() == 2);
            }
            assertEquals(2, run(leader, "SELECT * FROM item").asArray().size());

            // OR REPLACE incrementa a versao em todos
            assertEquals(1, version(leader, "FUNCTION", "dobro"));
            run(leader, "CREATE OR REPLACE FUNCTION dobro(x int) RETURNS int AS $$ return x * 3 $$");
            assertEquals(2, version(leader, "FUNCTION", "dobro"));
            for (Node f : followers) {
                await("versao 2 em " + f.id, () -> version(f, "FUNCTION", "dobro") == 2
                    && run(f, "RETURN dobro(2)").asLong() == 6);
            }

            // DROP replica
            run(leader, "DROP FUNCTION dobro");
            run(leader, "DROP PROCEDURE grava");
            for (Node f : followers) {
                await("drop em " + f.id, () -> {
                    AxonValue info = run(f, "INFO FOR DATABASE");
                    return info.asObject().get("functions").asObject().isEmpty()
                        && info.asObject().get("procedures").asObject().isEmpty();
                });
            }
            assertEquals(1, run(leader, "INFO FOR DATABASE").asObject().get("packages").asObject().size());
        } finally {
            nodes.forEach(Node::stop);
        }
    }

    @Test
    void seguidorReiniciadoRecompoeOsProgramas() throws Exception {
        List<Node> nodes = startCluster();
        try {
            Node leader = leader(nodes);
            Node follower = nodes.stream().filter(n -> n != leader).findFirst().orElseThrow();

            run(leader, "CREATE PACKAGE base AS $$\ndef a():\n    return 5\n$$");
            run(leader, "CREATE PACKAGE topo AS $$\nimport base\ndef b():\n    return base.a() + 1\n$$");
            run(leader, "CREATE FUNCTION triplo(x) AS $$ return x * 3 $$");
            run(leader, "CREATE OR REPLACE FUNCTION triplo(x) AS $$ return x * 3 + 0 $$");
            await("definicoes no seguidor", () -> run(follower, "RETURN triplo(2)").asLong() == 6);

            follower.stop();
            // enquanto o seguidor esta fora, o lider continua definindo
            run(leader, "CREATE FUNCTION novo() AS $$ return 'ok' $$");
            follower.start();

            await("topo::b no seguidor reiniciado", () -> run(follower, "RETURN topo::b()").asLong() == 6);
            await("triplo no seguidor reiniciado", () -> run(follower, "RETURN triplo(3)").asLong() == 9);
            await("novo no seguidor reiniciado", () -> "ok".equals(run(follower, "RETURN novo()").asString()));
            assertEquals(2, version(follower, "FUNCTION", "triplo"));
            assertNotNull(run(follower, "INFO FOR PACKAGE topo"));
            assertTrue(run(follower, "INFO FOR DATABASE").asObject().get("functions").asObject()
                .containsKey("novo"));
        } finally {
            nodes.forEach(Node::stop);
        }
    }
}
