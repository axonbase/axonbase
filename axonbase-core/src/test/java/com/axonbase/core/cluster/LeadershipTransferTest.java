package com.axonbase.core.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Transferência de liderança: passos e falhas")
@Timeout(60)
class LeadershipTransferTest {

    private static final LeadershipTransfer.Candidate AHEAD =
        new LeadershipTransfer.Candidate("n2", new InetSocketAddress("127.0.0.1", 7002), 9);
    private static final LeadershipTransfer.Candidate BEHIND =
        new LeadershipTransfer.Candidate("n3", new InetSocketAddress("127.0.0.1", 7003), 4);

    /** Um líder de mentira que obedece ao pedido de eleição imediata. */
    private static class FakeLeader implements LeadershipTransfer.Leader {
        final AtomicBoolean leading = new AtomicBoolean(true);
        final AtomicInteger asked = new AtomicInteger();
        volatile String leader = "n1";
        volatile List<LeadershipTransfer.Candidate> candidates = List.of(BEHIND, AHEAD);
        volatile long match = 10;
        volatile boolean accepts = true;
        volatile boolean steps = true;
        volatile boolean settled = true;
        volatile LeadershipTransfer.Candidate chosen;

        @Override
        public boolean leading() {
            return leading.get();
        }

        @Override
        public String leader() {
            return leader;
        }

        @Override
        public NotLeaderException notLeader() {
            return new NotLeaderException(leader);
        }

        @Override
        public List<LeadershipTransfer.Candidate> candidates() {
            return candidates;
        }

        @Override
        public long lastIndex() {
            return 10;
        }

        @Override
        public long appliedIndex() {
            return 10;
        }

        @Override
        public long matchIndex(LeadershipTransfer.Candidate candidate) {
            return match;
        }

        @Override
        public boolean settled(long index) {
            return settled;
        }

        @Override
        public boolean sendTimeoutNow(LeadershipTransfer.Candidate candidate) {
            asked.incrementAndGet();
            chosen = candidate;
            if (accepts && steps) {
                leader = candidate.node();
                leading.set(false);
            }
            return accepts;
        }
    }

    private final Object stateLock = new Object();
    private final FakeLeader node = new FakeLeader();
    private final LeadershipTransfer transfer = new LeadershipTransfer(stateLock, node);

    @Test
    @DisplayName("escolhe o membro mais adiantado e libera tudo no fim")
    void picksMostAdvanced() {
        TransferResult result = transfer.transfer(null, 2_000);

        assertTrue(result.transferred(), result.reason());
        assertEquals("n2", result.leader());
        assertEquals(AHEAD, node.chosen);
        assertFalse(transfer.blocksProposals());
    }

    @Test
    @DisplayName("aceita o alvo pelo nome ou pelo endereço")
    void honoursTarget() {
        assertEquals("n3", transfer.transfer("127.0.0.1:7003", 2_000).leader());
        node.leading.set(true);
        assertEquals("n3", transfer.transfer("n3", 2_000).leader());
    }

    @Test
    @DisplayName("recusa quando não é líder ou não há alvo")
    void refusals() {
        assertFalse(transfer.transfer("n9", 500).transferred());
        node.candidates = List.of();
        assertFalse(transfer.transfer(null, 500).transferred());
        node.leading.set(false);
        TransferResult result = transfer.transfer(null, 500);
        assertTrue(result.reason().contains("líder"), result.reason());
        assertEquals(0, node.asked.get());
    }

    @Test
    @DisplayName("alvo atrasado: desiste no prazo e as escritas voltam")
    void laggingTargetTimesOut() {
        node.match = 3;

        TransferResult result = transfer.transfer(null, 100);

        assertFalse(result.transferred());
        assertEquals(0, node.asked.get());
        assertFalse(transfer.blocksProposals());
        try (var permit = transfer.admit()) {
            assertTrue(node.leading());
        }
    }

    @Test
    @DisplayName("escrita aplicada mas ainda sem resposta: o pedido de eleição espera")
    void waitsUntilConfirmedWritesAreAnswered() throws Exception {
        node.settled = false;
        CompletableFuture<TransferResult> moving =
            CompletableFuture.supplyAsync(() -> transfer.transfer(null, 20_000));
        Thread.sleep(100);
        assertEquals(0, node.asked.get(), "o pedido saiu antes de quem escreveu receber a resposta");

        node.settled = true;

        assertTrue(moving.get(20, TimeUnit.SECONDS).transferred());
    }

    @Test
    @DisplayName("escritas admitidas durante muitas transferências nunca escapam da contagem")
    void admissionNeverEscapesTheCount() throws Exception {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger violations = new AtomicInteger();
        AtomicBoolean stop = new AtomicBoolean();
        LeadershipTransfer.Leader counting = new FakeLeader() {
            @Override
            public boolean sendTimeoutNow(LeadershipTransfer.Candidate candidate) {
                if (running.get() > 0) {
                    violations.incrementAndGet();
                }
                return false;
            }
        };
        LeadershipTransfer raced = new LeadershipTransfer(new Object(), counting);
        List<CompletableFuture<Void>> writers = java.util.stream.IntStream.range(0, 4)
            .mapToObj(n -> CompletableFuture.runAsync(() -> {
                while (!stop.get()) {
                    try (var permit = raced.admit()) {
                        running.incrementAndGet();
                        Thread.yield();
                        running.decrementAndGet();
                    }
                }
            })).toList();

        for (int i = 0; i < 300; i++) {
            raced.transfer(null, 1_000);
            // dá a quem esperava a chance de entrar antes da próxima transferência
            Thread.sleep(1);
        }
        stop.set(true);
        for (CompletableFuture<Void> writer : writers) {
            writer.get(20, TimeUnit.SECONDS);
        }

        assertEquals(0, violations.get(), "o pedido de eleição saiu com uma escrita em curso");
    }

    @Test
    @DisplayName("alvo que recusa ou não assume: o líder segue no cargo")
    void targetRefusesOrNeverTakesOver() {
        node.accepts = false;
        assertFalse(transfer.transfer(null, 200).transferred());
        node.accepts = true;
        node.steps = false;
        assertFalse(transfer.transfer(null, 100).transferred());
        assertTrue(node.leading());
        assertFalse(transfer.blocksProposals());
    }

    @Test
    @DisplayName("escrita em curso termina antes; escrita nova espera e recebe NOT_LEADER sem executar")
    void waitsForRunningWritesAndHoldsNewOnes() throws Exception {
        CommitCoordinator.WritePermit running = transfer.admit();
        CompletableFuture<TransferResult> moving =
            CompletableFuture.supplyAsync(() -> transfer.transfer(null, 20_000));
        Thread.sleep(100);
        assertEquals(0, node.asked.get(), "o pedido saiu com uma escrita em curso");
        CompletableFuture<Void> late = CompletableFuture.runAsync(() -> transfer.admit().close());
        Thread.sleep(100);
        assertFalse(late.isDone(), "a escrita nova não esperou");

        // a mesma thread pode abrir uma escrita aninhada mesmo com a transferência esperando
        try (var nested = transfer.admit()) {
            assertTrue(node.leading());
        }
        running.close();

        assertTrue(moving.get(20, TimeUnit.SECONDS).transferred());
        var failure = assertThrows(java.util.concurrent.ExecutionException.class,
            () -> late.get(20, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof NotLeaderException, String.valueOf(failure.getCause()));
    }

    @Test
    @DisplayName("uma transferência de cada vez")
    void onlyOneAtATime() throws Exception {
        CommitCoordinator.WritePermit running = transfer.admit();
        CompletableFuture<TransferResult> first =
            CompletableFuture.supplyAsync(() -> transfer.transfer(null, 20_000));
        Thread.sleep(100);

        TransferResult second = transfer.transfer(null, 100);

        assertFalse(second.transferred());
        running.close();
        assertTrue(first.get(20, TimeUnit.SECONDS).transferred());
    }
}
