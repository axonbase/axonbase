package com.axonbase.core;

import com.axonbase.core.cluster.AppliedBatchListener;
import com.axonbase.core.cluster.ClusterConfig;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.cluster.RaftNode;
import com.axonbase.core.cluster.RaftPayload;
import com.axonbase.core.cluster.RaftWire;
import com.axonbase.core.control.ControlStore;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reconstrução do catálogo a partir do que ficou durável: o log Raft de um nó e o
 * snapshot de controle no storage.
 */
@DisplayName("Cluster: catálogo reconstruído do log e do snapshot")
class ControlReplayTest {

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
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

    /**
     * O log é a única coisa preservada: o backend é novo, e ainda assim o catálogo,
     * as identidades e os dados voltam.
     */
    @Test
    void catalogoReconstruidoAPartirDoLogRaft(@TempDir Path dir) {
        MemoryBackend origin = new MemoryBackend();
        Datastore source = new Datastore(origin);
        Session s = session();
        source.execute("DEFINE ANALYZER pt LOWERCASE", s, null);
        source.execute("DEFINE TABLE person SCHEMAFULL", s, null);
        source.execute("DEFINE FIELD age ON TABLE person TYPE int DEFAULT 18", s, null);
        source.execute("DEFINE INDEX busca ON TABLE person COLUMNS name SEARCH ANALYZER pt", s, null);
        source.execute("DEFINE USER alice ON DATABASE PASSWORD \"s3cr3t\" ROLES editor", s, null);
        source.execute("CREATE person:1 CONTENT { age: 30 }", s, null);

        // Tudo que o nó recebeu vira uma entrada de log num nó ainda vazio.
        CommittedBatch batch = snapshotOf(origin);
        RaftNode node = new RaftNode("n1", dir, new MemoryBackend());
        node.handle(new RaftWire.Request(RaftWire.APPEND, "g", 1, "leader", 1, 1,
            RaftPayload.append(0, 0, batch)));
        assertEquals(1, node.commitIndex());

        // Novo backend, mesmo diretório de log: o replay do log repovoa o storage.
        MemoryBackend rebuilt = new MemoryBackend();
        RaftNode restarted = new RaftNode("n1", dir, rebuilt);
        assertEquals(1, restarted.commitIndex());
        assertTrue(rebuilt.get(ControlStore.KEY).isPresent(),
            "o snapshot de controle não veio no log");

        Datastore recovered = new Datastore(rebuilt);
        assertTrue(recovered.databases("n").contains("d"));
        AxonValue info = recovered.execute("INFO FOR TABLE person", session(), null);
        assertTrue(info.asObject().get("fields").asObject().containsKey("age"));
        assertTrue(info.asObject().get("indexes").asObject().containsKey("busca"));
        assertNotNull(recovered.authCatalog().verify("alice", "s3cr3t", "n", "d"));
        assertEquals(1, recovered.execute("SELECT * FROM person", session(), null).asArray().size());
    }

    /**
     * O listener acoplado depois do nó reprocessa o log já aplicado, que é o caso
     * real: o {@code ClusterRuntime} sobe antes do {@code Datastore}.
     */
    @Test
    void listenerAcopladoDepoisReprocessaOLogJaAplicado(@TempDir Path dir) {
        MemoryBackend origin = new MemoryBackend();
        Datastore source = new Datastore(origin);
        source.execute("DEFINE TABLE person SCHEMAFULL", session(), null);
        source.execute("DEFINE USER alice ON DATABASE PASSWORD \"s3cr3t\"", session(), null);

        // O datastore nasce antes do batch chegar, com o storage ainda vazio.
        MemoryBackend target = new MemoryBackend();
        RaftNode node = new RaftNode("n1", dir, target);
        Datastore attached = new Datastore(target);
        assertTrue(attached.namespaces().isEmpty());

        // O nó aplica o batch sem listener: os bytes entram, o catálogo não.
        node.handle(new RaftWire.Request(RaftWire.APPEND, "g", 1, "leader", 1, 1,
            RaftPayload.append(0, 0, snapshotOf(origin))));
        assertTrue(attached.namespaces().isEmpty(), "catálogo mudou sem listener acoplado");

        List<AppliedBatchListener.AppliedBatch> seen = new ArrayList<>();
        node.listener(applied -> {
            seen.add(applied);
            attached.onReplicatedBatch(applied);
        });
        assertEquals(1, seen.size(), "o log aplicado não foi reprocessado no acoplamento");
        assertTrue(attached.databases("n").contains("d"));
        assertNotNull(attached.authCatalog().verify("alice", "s3cr3t", "n", "d"));
    }

    /** Caminho completo por TCP: DDL no líder chega ao catálogo do seguidor. */
    @Test
    void ddlReplicadoPorTcpChegaAoCatalogoDoSeguidor() throws Exception {
        for (int setupAttempt = 0; setupAttempt < 3; setupAttempt++) {
            try {
                List<Integer> ports = reservePorts(2);
                InetSocketAddress a1 = new InetSocketAddress("127.0.0.1", ports.get(0));
                InetSocketAddress a2 = new InetSocketAddress("127.0.0.1", ports.get(1));
                MemoryBackend b1 = new MemoryBackend();
                MemoryBackend b2 = new MemoryBackend();
                try (ClusterRuntime n1 = new ClusterRuntime(new ClusterConfig("n1", "g", a1, List.of(a2)),
                        Files.createTempDirectory("tcp-n1"), b1);
                     ClusterRuntime n2 = new ClusterRuntime(new ClusterConfig("n2", "g", a2, List.of(a1)),
                        Files.createTempDirectory("tcp-n2"), b2)) {
                    Datastore ds1 = new Datastore(b1);
                    Datastore ds2 = new Datastore(b2);
                    n1.appliedBatchListener(ds1.appliedBatchListener());
                    n2.appliedBatchListener(ds2.appliedBatchListener());
                    ds1.commitCoordinator(n1, "n1");
                    ds2.commitCoordinator(n2, "n2");

                    ClusterRuntime leaderRuntime = null;
                    for (int attempt = 0; attempt < 60 && leaderRuntime == null; attempt++) {
                        if (n1.role() == ClusterRole.LEADER) {
                            leaderRuntime = n1;
                        } else if (n2.role() == ClusterRole.LEADER) {
                            leaderRuntime = n2;
                        } else {
                            Thread.sleep(75);
                        }
                    }
                    assertNotNull(leaderRuntime, "nenhum líder eleito");
                    Datastore leader = leaderRuntime == n1 ? ds1 : ds2;
                    Datastore follower = leaderRuntime == n1 ? ds2 : ds1;

                    leader.execute("DEFINE TABLE person SCHEMAFULL", session(), null);
                    leader.execute("DEFINE FIELD age ON TABLE person TYPE int DEFAULT 18", session(), null);
                    leader.execute("DEFINE USER alice ON DATABASE PASSWORD \"s3cr3t\"", session(), null);
                    leader.execute("CREATE person:1 CONTENT { age: 30 }", session(), null);

                    for (int attempt = 0; attempt < 40 && follower.namespaces().isEmpty(); attempt++) {
                        Thread.sleep(75);
                    }
                    AxonValue info = follower.execute("INFO FOR TABLE person", session(), null);
                    assertTrue(info.asObject().get("fields").asObject().containsKey("age"));
                    assertNotNull(follower.authCatalog().verify("alice", "s3cr3t", "n", "d"));
                    AxonValue rows = follower.execute("SELECT * FROM person", session(), null);
                    for (int attempt = 0; attempt < 40 && rows.asArray().isEmpty(); attempt++) {
                        Thread.sleep(75);
                        rows = follower.execute("SELECT * FROM person", session(), null);
                    }
                    assertEquals(1, rows.asArray().size());
                }
                return;
            } catch (BindException e) {
                if (setupAttempt == 2) {
                    throw e;
                }
            }
        }
    }

    /** Empacota o estado inteiro de um backend como um único batch replicável. */
    private static CommittedBatch snapshotOf(MemoryBackend backend) {
        Map<String, byte[]> puts = new java.util.LinkedHashMap<>();
        for (String key : backend.keysWithPrefix("")) {
            puts.put(key, backend.get(key).orElseThrow());
        }
        return new CommittedBatch("bootstrap", puts, Set.of());
    }
}
