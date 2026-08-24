package com.axonbase.core;

import com.axonbase.core.cluster.NotLeaderException;
import com.axonbase.core.cluster.QuorumUnavailableException;
import com.axonbase.core.cluster.RaftCommitCoordinator;
import com.axonbase.core.cluster.RaftGroup;
import com.axonbase.core.control.ControlCommand;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.LiveBus;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.VersionedKvBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cluster visto pelo datastore: DDL e identidades replicadas, escrita recusada
 * fora do líder, live query num seguidor e nada aplicado sem quórum.
 */
@DisplayName("Cluster: catálogo, identidades e eventos replicados")
class ClusterDatastoreTest {

    /**
     * Um cluster de datastores reais sobre um grupo Raft embutido: cada nó tem o
     * próprio backend e o próprio catálogo em memória, e o grupo é o único caminho
     * entre eles.
     */
    private static final class Cluster {
        final RaftGroup group;
        final Map<String, Datastore> nodes = new LinkedHashMap<>();
        final Map<String, VersionedKvBackend> backends = new LinkedHashMap<>();

        Cluster(String... members) {
            Map<String, VersionedKvBackend> assigned = new LinkedHashMap<>();
            for (String member : members) {
                assigned.put(member, new MemoryBackend());
            }
            backends.putAll(assigned);
            group = RaftGroup.over("n/d", assigned);
            assigned.forEach((member, backend) -> {
                Datastore ds = new Datastore(backend);
                ds.commitCoordinator(new RaftCommitCoordinator(group), member);
                group.listener(member, ds.appliedBatchListener());
                nodes.put(member, ds);
            });
        }

        Datastore node(String member) {
            return nodes.get(member);
        }

        String leader() {
            return group.leader();
        }
    }

    private static Session session() {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return s;
    }

    // ------------------------------------------------------------------
    // 1. DDL e auth aplicados no líder
    // ------------------------------------------------------------------

    @Test
    void ddlEAuthNoLiderGeramComandosDeControle() {
        Cluster cluster = new Cluster("n1", "n2");
        Datastore leader = cluster.node("n1");
        assertEquals("n1", cluster.leader());

        Session s = session();
        leader.execute("DEFINE ANALYZER pt LOWERCASE", s, null);
        leader.execute("DEFINE TABLE person SCHEMAFULL", s, null);
        leader.execute("DEFINE FIELD age ON TABLE person TYPE int DEFAULT 18", s, null);
        leader.execute("DEFINE INDEX busca ON TABLE person COLUMNS name SEARCH ANALYZER pt", s, null);
        leader.execute("DEFINE EVENT audit ON TABLE person WHEN $event = \"CREATE\" "
            + "THEN (CREATE changes CONTENT { kind: \"person\" })", s, null);
        leader.execute("DEFINE USER alice ON DATABASE PASSWORD \"s3cr3t\" ROLES editor", s, null);
        leader.execute("DEFINE ACCESS login ON DATABASE", s, null);

        List<ControlCommand.Kind> kinds = leader.controlSnapshot().commands().stream()
            .map(ControlCommand::kind).toList();
        assertTrue(kinds.containsAll(List.of(ControlCommand.Kind.DATABASE,
            ControlCommand.Kind.ANALYZER, ControlCommand.Kind.TABLE, ControlCommand.Kind.FIELD,
            ControlCommand.Kind.INDEX, ControlCommand.Kind.EVENT, ControlCommand.Kind.ACCESS,
            ControlCommand.Kind.USER)), "faltam tipos no plano de controle: " + kinds);

        // A definição guardada é o texto AxonQL, e a de usuário nunca traz a senha.
        ControlCommand user = leader.controlSnapshot().commands().stream()
            .filter(c -> c.kind() == ControlCommand.Kind.USER).findFirst().orElseThrow();
        assertTrue(user.definition().contains("PASSHASH"), user.definition());
        assertFalse(user.definition().contains("s3cr3t"), user.definition());
        assertNotNull(leader.authCatalog().verify("alice", "s3cr3t", "n", "d"));
    }

    // ------------------------------------------------------------------
    // 2. DDL e auth aplicados no seguidor
    // ------------------------------------------------------------------

    @Test
    void ddlEAuthChegamAoSeguidorPeloBatchReplicado() {
        Cluster cluster = new Cluster("n1", "n2");
        Datastore leader = cluster.node("n1");
        Datastore follower = cluster.node("n2");

        Session s = session();
        leader.execute("DEFINE ANALYZER pt LOWERCASE", s, null);
        leader.execute("DEFINE TABLE person SCHEMAFULL", s, null);
        leader.execute("DEFINE FIELD age ON TABLE person TYPE int DEFAULT 18", s, null);
        leader.execute("DEFINE INDEX busca ON TABLE person COLUMNS name SEARCH ANALYZER pt", s, null);
        leader.execute("DEFINE EVENT audit ON TABLE person WHEN $event = \"CREATE\" "
            + "THEN (CREATE changes CONTENT { kind: \"person\" })", s, null);
        leader.execute("DEFINE USER alice ON DATABASE PASSWORD \"s3cr3t\" ROLES editor", s, null);
        leader.execute("DEFINE ACCESS login ON DATABASE", s, null);

        assertTrue(follower.databases("n").contains("d"));
        AxonValue info = follower.execute("INFO FOR TABLE person", session(), null);
        assertTrue(info.asObject().get("fields").asObject().containsKey("age"));
        assertTrue(info.asObject().get("indexes").asObject().containsKey("busca"));
        assertTrue(info.asObject().get("events").asObject().containsKey("audit"));

        // O hash atravessou a replicação: o seguidor autentica sem nunca ver a senha.
        assertNotNull(follower.authCatalog().verify("alice", "s3cr3t", "n", "d"));
        assertNull(follower.authCatalog().verify("alice", "errada", "n", "d"));
        assertNotNull(follower.authCatalog().access("login", "n", "d"));
        assertEquals(leader.authCatalog().users().getFirst().hashHex(),
            follower.authCatalog().users().getFirst().hashHex());
    }

    // ------------------------------------------------------------------
    // 3. DDL e auth sobrevivem a um restart
    // ------------------------------------------------------------------

    @Test
    void catalogoEAuthSobrevivemAoRestartDoNo() {
        Cluster cluster = new Cluster("n1", "n2");
        Session s = session();
        cluster.node("n1").execute("DEFINE ANALYZER pt LOWERCASE", s, null);
        cluster.node("n1").execute("DEFINE TABLE person SCHEMAFULL", s, null);
        cluster.node("n1").execute("DEFINE FIELD age ON TABLE person TYPE int DEFAULT 18", s, null);
        cluster.node("n1").execute("DEFINE INDEX busca ON TABLE person COLUMNS name "
            + "SEARCH ANALYZER pt", s, null);
        cluster.node("n1").execute("DEFINE USER alice ON DATABASE PASSWORD \"s3cr3t\"", s, null);

        // Reabrir o mesmo storage é o restart: nada além dos bytes é reaproveitado.
        Datastore restarted = new Datastore(cluster.backends.get("n2"));
        assertTrue(restarted.databases("n").contains("d"));
        AxonValue info = restarted.execute("INFO FOR TABLE person", session(), null);
        assertTrue(info.asObject().get("fields").asObject().containsKey("age"));
        assertTrue(info.asObject().get("indexes").asObject().containsKey("busca"));
        assertNotNull(restarted.authCatalog().verify("alice", "s3cr3t", "n", "d"));

        // O mesmo vale para o líder, que aplicou tudo pelo caminho de definição.
        Datastore restartedLeader = new Datastore(cluster.backends.get("n1"));
        assertTrue(restartedLeader.execute("INFO FOR TABLE person", session(), null)
            .asObject().get("fields").asObject().containsKey("age"));
        assertNotNull(restartedLeader.authCatalog().verify("alice", "s3cr3t", "n", "d"));
        assertNull(restartedLeader.authCatalog().verify("alice", "errada", "n", "d"));
    }

    // ------------------------------------------------------------------
    // 4. Seguidor recusa escrita
    // ------------------------------------------------------------------

    @Test
    void seguidorRecusaEscritaComNotLeaderEInformaOLider() {
        Cluster cluster = new Cluster("n1", "n2");
        Datastore follower = cluster.node("n2");
        Session s = session();

        NotLeaderException moved = assertThrows(NotLeaderException.class,
            () -> follower.execute("CREATE t CONTENT {x: 1}", s, null));
        assertEquals("n1", moved.leader());
        assertEquals(0, follower.execute("SELECT * FROM t", session(), null).asArray().size());

        // Recusar DDL num seguidor também não pode deixar resíduo no catálogo.
        assertThrows(NotLeaderException.class,
            () -> follower.execute("DEFINE TABLE person SCHEMAFULL", session(), null));
        assertTrue(follower.controlSnapshot().commands().stream()
            .noneMatch(c -> c.kind() == ControlCommand.Kind.TABLE && c.name().equals("person")));
    }

    // ------------------------------------------------------------------
    // 5. Live query num nó que não é líder
    // ------------------------------------------------------------------

    @Test
    void liveQueryEmNoNaoLiderRecebeMudancaConfirmada() {
        Cluster cluster = new Cluster("n1", "n2");
        Datastore leader = cluster.node("n1");
        Datastore follower = cluster.node("n2");

        List<LiveBus.Notification> received = new ArrayList<>();
        Session subscriber = session();
        subscriber.liveListener(received::add);
        follower.execute("LIVE SELECT * FROM t", subscriber, null);

        leader.execute("CREATE t:1 CONTENT { x: 1 }", session(), null);
        assertEquals(1, received.size(), "seguidor não recebeu o CREATE confirmado");
        assertEquals(LiveBus.CREATE, received.getFirst().action());
        assertEquals(1, received.getFirst().result().asObject().get("x").asLong());

        leader.execute("UPDATE t:1 SET x = 2", session(), null);
        assertEquals(2, received.size());
        assertEquals(LiveBus.UPDATE, received.get(1).action());
        assertEquals(2, received.get(1).result().asObject().get("x").asLong());

        leader.execute("DELETE t:1", session(), null);
        assertEquals(3, received.size());
        assertEquals(LiveBus.DELETE, received.get(2).action());
    }

    @Test
    void liveQueryNoLiderNaoDuplicaComOFanoutDoConsenso() {
        Cluster cluster = new Cluster("n1", "n2");
        Datastore leader = cluster.node("n1");

        List<LiveBus.Notification> received = new ArrayList<>();
        Session subscriber = session();
        subscriber.liveListener(received::add);
        leader.execute("LIVE SELECT * FROM t", subscriber, null);

        leader.execute("CREATE t:1 CONTENT { x: 1 }", session(), null);
        assertEquals(1, received.size(), "o líder notificou duas vezes: " + received);
    }

    // ------------------------------------------------------------------
    // 6. Sem quórum: nada aplicado, nada notificado
    // ------------------------------------------------------------------

    @Test
    void semQuorumNaoAplicaNemNotifica() {
        Cluster cluster = new Cluster("n1", "n2", "n3");
        Datastore leader = cluster.node("n1");

        List<LiveBus.Notification> onLeader = new ArrayList<>();
        Session subscriber = session();
        subscriber.liveListener(onLeader::add);
        leader.execute("LIVE SELECT * FROM t", subscriber, null);

        List<LiveBus.Notification> onFollower = new ArrayList<>();
        Session remote = session();
        remote.liveListener(onFollower::add);
        cluster.node("n2").execute("LIVE SELECT * FROM t", remote, null);

        cluster.group.stop("n2");
        cluster.group.stop("n3");

        Session writer = session();
        leader.execute("BEGIN", writer, null);
        leader.execute("CREATE t CONTENT {x: 1}", writer, null);
        assertThrows(QuorumUnavailableException.class, () -> leader.execute("COMMIT", writer, null));

        assertTrue(onLeader.isEmpty(), "notificou no líder sem quórum: " + onLeader);
        assertTrue(onFollower.isEmpty(), "notificou no seguidor sem quórum: " + onFollower);
        assertEquals(0, leader.execute("SELECT * FROM t", session(), null).asArray().size());
    }

    @Test
    void ddlSemQuorumNaoEntraNoPlanoDeControle() {
        Cluster cluster = new Cluster("n1", "n2", "n3");
        Datastore leader = cluster.node("n1");
        cluster.group.stop("n2");
        cluster.group.stop("n3");

        assertThrows(QuorumUnavailableException.class,
            () -> leader.execute("DEFINE TABLE person SCHEMAFULL", session(), null));
        assertTrue(leader.controlSnapshot().commands().stream()
            .noneMatch(c -> c.kind() == ControlCommand.Kind.TABLE && c.name().equals("person")));
    }

    // ------------------------------------------------------------------
    // 7. Catálogo reconstruído a partir do snapshot de catch-up
    // ------------------------------------------------------------------

    @Test
    void catalogoReconstruidoAPartirDoSnapshotDeCatchUp() {
        Cluster cluster = new Cluster("n1", "n2", "n3");
        Datastore leader = cluster.node("n1");
        Datastore late = cluster.node("n3");

        // n3 fica fora enquanto o DDL e os dados são confirmados pelos outros dois.
        cluster.group.stop("n3");
        Session s = session();
        leader.execute("DEFINE TABLE person SCHEMAFULL", s, null);
        leader.execute("DEFINE FIELD age ON TABLE person TYPE int DEFAULT 18", s, null);
        leader.execute("DEFINE USER alice ON DATABASE PASSWORD \"s3cr3t\"", s, null);
        leader.execute("CREATE person:1 CONTENT { age: 30 }", s, null);
        assertTrue(late.namespaces().isEmpty(), "n3 não deveria conhecer nada ainda");

        cluster.group.start("n3");

        AxonValue info = late.execute("INFO FOR TABLE person", session(), null);
        assertTrue(info.asObject().get("fields").asObject().containsKey("age"));
        assertNotNull(late.authCatalog().verify("alice", "s3cr3t", "n", "d"));
        assertEquals(1, late.execute("SELECT * FROM person", session(), null).asArray().size());
    }

    @Test
    void snapshotDeCatchUpNotificaLiveQueryDoNoQueVoltou() {
        Cluster cluster = new Cluster("n1", "n2", "n3");
        Datastore leader = cluster.node("n1");

        List<LiveBus.Notification> received = new ArrayList<>();
        Session subscriber = session();
        subscriber.liveListener(received::add);
        cluster.node("n3").execute("LIVE SELECT * FROM t", subscriber, null);

        cluster.group.stop("n3");
        leader.execute("CREATE t:1 CONTENT { x: 1 }", session(), null);
        assertTrue(received.isEmpty(), "nó parado não pode receber evento");

        cluster.group.start("n3");
        assertEquals(1, received.size(), "o catch-up não entregou a mudança perdida");
        assertEquals(LiveBus.CREATE, received.getFirst().action());
    }

    @Test
    void mudancaDeDadosEmNoUnicoContinuaFuncionandoSemCluster() {
        Datastore ds = Datastore.memory();
        List<LiveBus.Notification> received = new ArrayList<>();
        Session subscriber = session();
        subscriber.liveListener(received::add);
        ds.execute("LIVE SELECT * FROM t", subscriber, null);
        ds.execute("CREATE t:1 CONTENT { x: 1 }", session(), null);
        assertEquals(1, received.size());
        assertEquals(LiveBus.CREATE, received.getFirst().action());
    }
}
