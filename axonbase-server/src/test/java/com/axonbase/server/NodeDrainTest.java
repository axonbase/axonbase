package com.axonbase.server;

import com.axonbase.core.cluster.TransferResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Drenagem de um nó")
@Timeout(60)
class NodeDrainTest {

    private static final class FakeNode implements NodeDrain.Node {
        final AtomicBoolean leading = new AtomicBoolean();
        final AtomicBoolean abstaining = new AtomicBoolean();
        final AtomicInteger refusals = new AtomicInteger();
        final AtomicLong inFlight = new AtomicLong();

        @Override
        public boolean leading() {
            return leading.get();
        }

        @Override
        public TransferResult transfer(Duration timeout) {
            if (refusals.getAndDecrement() > 0) {
                return new TransferResult(false, "", "sem alvo", 1);
            }
            leading.set(false);
            return new TransferResult(true, "n2", "", 1);
        }

        @Override
        public void abstain(boolean value) {
            abstaining.set(value);
        }

        @Override
        public long dataRequestsInFlight() {
            return inFlight.get();
        }
    }

    private final FakeNode node = new FakeNode();

    @Test
    @DisplayName("passa o cargo, deixa de disputar eleição e termina drenado")
    void drainsLeader() {
        node.leading.set(true);
        node.refusals.set(1);
        NodeDrain drain = new NodeDrain(node, 0, 10_000);
        assertFalse(drain.draining());

        NodeDrain.Status status = drain.drainAndWait();

        assertEquals(NodeDrain.State.DRAINED, status.state(), status.reason());
        assertEquals("n2", status.leader());
        assertTrue(node.abstaining.get());
        assertTrue(drain.draining());
    }

    @Test
    @DisplayName("líder sem para quem passar o cargo falha com o motivo")
    void failsWithoutTarget() {
        node.leading.set(true);
        node.refusals.set(Integer.MAX_VALUE);

        NodeDrain.Status status = new NodeDrain(node, 0, 100).drainAndWait();

        assertEquals(NodeDrain.State.FAILED, status.state());
        assertTrue(status.reason().contains("sem alvo"), status.reason());
    }

    @Test
    @DisplayName("espera as requisições em curso e desiste no prazo")
    void waitsForRequests() throws Exception {
        node.inFlight.set(1);
        NodeDrain drain = new NodeDrain(node, 0, 20_000);
        drain.start();
        Thread.sleep(100);
        assertEquals(NodeDrain.State.DRAINING, drain.status().state());

        node.inFlight.set(0);

        assertEquals(NodeDrain.State.DRAINED, drain.drainAndWait().state());
        node.inFlight.set(2);
        NodeDrain late = new NodeDrain(node, 0, 50);
        assertEquals(NodeDrain.State.FAILED, late.drainAndWait().state());
    }

    @Test
    @DisplayName("se o cargo voltar durante a espera, passa de novo antes de se declarar drenado")
    void movesLeadershipThatArrivesWhileWaiting() throws Exception {
        node.inFlight.set(1);
        NodeDrain drain = new NodeDrain(node, 0, 20_000);
        drain.start();
        Thread.sleep(100);

        node.leading.set(true);
        node.inFlight.set(0);
        NodeDrain.Status status = drain.drainAndWait();

        assertEquals(NodeDrain.State.DRAINED, status.state(), status.reason());
        assertEquals("n2", status.leader());
        assertFalse(node.leading.get(), "o nó se declarou drenado ainda liderando");
    }

    @Test
    @DisplayName("um valor mal escrito é recusado antes de o servidor subir")
    void validatesSettings() {
        AxonServer.validateSettings(name -> null);
        for (java.util.Map<String, String> env : java.util.List.of(
                java.util.Map.of("AXON_QOS", "talvez"),
                java.util.Map.of("AXON_CLUSTER_HTTP_PEERS", "n1=a:8000"),
                java.util.Map.of("AXON_DRAIN_GRACE_MS", "cinco"),
                java.util.Map.of("AXON_DRAIN_TIMEOUT_MS", "1s"))) {
            assertThrows(RuntimeException.class, () -> AxonServer.validateSettings(env::get), env.toString());
        }
    }

    @Test
    @DisplayName("desfazer volta a atender e a disputar eleição")
    void undrain() throws Exception {
        node.inFlight.set(1);
        NodeDrain drain = new NodeDrain(node, 0, 20_000);
        drain.start();

        NodeDrain.Status status = drain.undrain();
        Thread.sleep(100);

        assertEquals(NodeDrain.State.ACTIVE, status.state());
        assertEquals(NodeDrain.State.ACTIVE, drain.status().state());
        assertFalse(node.abstaining.get());
        assertFalse(drain.draining());
    }

    @Test
    @DisplayName("o redirecionamento usa a URL configurada e cai no endereço do Raft sem ela")
    void leaderRedirect() {
        LeaderRedirect redirect = LeaderRedirect.parse("n1=http://a:8000/, n2=https://b:9000");

        assertEquals("http://a:8000", redirect.base("n1", "10.0.0.1:9000"));
        assertEquals("https://b:9000", redirect.base("n2", ""));
        assertEquals("http://10.0.0.3:9000", redirect.base("n3", "10.0.0.3:9000"));
        assertEquals("", redirect.base("n3", ""));
        assertEquals("http://10.0.0.1:9000", LeaderRedirect.parse(null).base("n1", "10.0.0.1:9000"));
        assertThrows(IllegalArgumentException.class, () -> LeaderRedirect.parse("n1=a:8000"));
    }
}
