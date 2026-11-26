package com.axonbase.core.cluster;

import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Um seguidor desta versão sob um líder da 0.10.1. O protocolo antigo manda uma entrada por requisição, com o termo
 * do mandato, e informa o termo verdadeiro só para a entrada anterior.
 */
@DisplayName("Raft: seguidor novo sob líder antigo")
class RaftLegacyLeaderTest {

    @TempDir
    Path dir;

    private static CommittedBatch batch(String key) {
        return new CommittedBatch("tx-" + key, Map.of(key, key.getBytes(StandardCharsets.UTF_8)), Set.of());
    }

    private static RaftWire.Response append(RaftNode follower, long term, long index, long previousTerm,
                                            long commit, CommittedBatch batch) {
        return follower.handle(new RaftWire.Request(RaftWire.APPEND, "g", term, "antigo", index, commit,
            RaftPayload.append(index - 1, previousTerm, batch)));
    }

    private static RaftWire.Response heartbeat(RaftNode follower, long term, long commit) {
        return follower.handle(new RaftWire.Request(RaftWire.APPEND, "g", term, "antigo", 0, commit, new byte[0]));
    }

    /**
     * O laço de replicação da 0.10.1: envia a próxima entrada, avança quando aceita e recua uma posição quando
     * recusa. Cada posição leva o termo com que o líder a guarda.
     */
    private static boolean replicate(RaftNode follower, long term, List<Long> leaderTerms,
                                     List<CommittedBatch> entries, long next, long commit) {
        for (int attempt = 0; attempt < 50 && next <= entries.size(); attempt++) {
            long previousTerm = next == 1 ? 0 : leaderTerms.get((int) next - 2);
            if (append(follower, term, next, previousTerm, commit, entries.get((int) next - 1)).accepted()) {
                next++;
            } else {
                next = Math.max(1, next - 1);
            }
        }
        return next > entries.size();
    }

    @Test
    @DisplayName("líder recém-eleito entrega a entrada do mandato anterior e a do próprio mandato")
    void leaderWithEntryFromPreviousTermMakesProgress() throws Exception {
        try (RaftNode follower = new RaftNode("n2", dir, new MemoryBackend())) {
            assertTrue(append(follower, 1, 1, 0, 0, batch("a")).accepted());
            // A entrada 2 é do mandato 2 e este seguidor não a recebeu; o líder antigo foi eleito no mandato 3.
            List<CommittedBatch> entries = List.of(batch("a"), batch("b"), batch("c"));

            boolean done = replicate(follower, 3, List.of(1L, 2L, 3L), entries, 3, 1);

            assertTrue(done, "o líder antigo ficou em laço: último índice do seguidor " + follower.lastIndex());
            heartbeat(follower, 3, 3);
            assertEquals(3, follower.commitIndex());
            assertTrue(follower.stateMachine().get("b").isPresent());
            assertTrue(follower.stateMachine().get("c").isPresent());
            assertEquals(3, follower.lastTermAt(2), "o termo gravado nunca pode ser rebaixado");
        }
    }

    @Test
    @DisplayName("reenviar uma entrada já corrigida não apaga as seguintes")
    void resendKeepsLaterEntries() throws Exception {
        try (RaftNode follower = new RaftNode("n2", dir, new MemoryBackend())) {
            assertTrue(append(follower, 1, 1, 0, 0, batch("a")).accepted());
            replicate(follower, 3, List.of(1L, 2L, 3L), List.of(batch("a"), batch("b"), batch("c")), 3, 1);

            assertTrue(append(follower, 3, 2, 1, 1, batch("b")).accepted());

            assertEquals(3, follower.lastIndex());
        }
    }

    @Test
    @DisplayName("líder antigo que guarda a entrada com outro termo também progride")
    void leaderThatTaggedTheEntryDifferentlyMakesProgress() throws Exception {
        try (RaftNode follower = new RaftNode("n2", dir, new MemoryBackend())) {
            assertTrue(append(follower, 1, 1, 0, 0, batch("a")).accepted());
            // Este seguidor tem a entrada 2 com o termo verdadeiro (1); o líder antigo a recebeu num reenvio do
            // mandato 2 e a guarda com esse termo.
            assertTrue(append(follower, 1, 2, 1, 1, batch("b")).accepted());

            boolean done = replicate(follower, 3, List.of(1L, 2L, 3L),
                List.of(batch("a"), batch("b"), batch("c")), 3, 1);

            assertTrue(done, "o líder antigo ficou em laço: último índice do seguidor " + follower.lastIndex());
            heartbeat(follower, 3, 3);
            assertEquals(3, follower.commitIndex());
            assertEquals(3, follower.lastTermAt(2), "o termo gravado nunca pode ser rebaixado");
            assertTrue(follower.stateMachine().get("c").isPresent());
        }
    }

    /** O snapshot da 0.10.1: o estado aplicado, com o índice do fim do log do líder. */
    private static RaftWire.Response snapshot(RaftNode follower, long index, Map<String, byte[]> state) {
        return follower.handle(new RaftWire.Request(RaftWire.SNAPSHOT, "g", 3, "antigo", index, 2,
            RaftPayload.encode(new CommittedBatch("snapshot-" + index, state, Set.of(), true))));
    }

    @Test
    @DisplayName("snapshot antigo que não traz tudo até o índice dele não passa por cima de um nó com dados")
    void legacySnapshotCannotSkipEntries() throws Exception {
        try (RaftNode follower = new RaftNode("n2", dir, new MemoryBackend())) {
            assertTrue(append(follower, 3, 1, 0, 0, batch("a")).accepted());
            assertTrue(append(follower, 3, 2, 3, 1, batch("b")).accepted());
            Map<String, byte[]> applied = Map.of("a", "a".getBytes(StandardCharsets.UTF_8));
            Map<String, byte[]> staleMarker = Map.of("a", "a".getBytes(StandardCharsets.UTF_8),
                RaftApplier.APPLIED_KEY, RaftApplier.marker(1, 1));

            assertFalse(snapshot(follower, 4, applied).accepted());
            assertFalse(snapshot(follower, 4, staleMarker).accepted());
            assertEquals(1, follower.commitIndex());

            assertTrue(append(follower, 3, 3, 3, 2, batch("c")).accepted());
            assertTrue(append(follower, 3, 4, 3, 2, batch("d")).accepted());
            heartbeat(follower, 3, 4);
            assertTrue(follower.stateMachine().get("c").isPresent());
            assertTrue(follower.stateMachine().get("d").isPresent());
        }
    }

    @Test
    @DisplayName("um nó vazio ainda aceita o snapshot antigo, sem o qual não entraria")
    void emptyNodeAcceptsLegacySnapshot() throws Exception {
        try (RaftNode follower = new RaftNode("n2", dir, new MemoryBackend())) {
            assertTrue(snapshot(follower, 4, Map.of("a", "a".getBytes(StandardCharsets.UTF_8))).accepted());
            assertEquals(4, follower.commitIndex());
        }
    }

    @Test
    @DisplayName("o batimento do líder antigo não confirma uma cauda que não veio dele")
    void heartbeatDoesNotCommitUncheckedTail() throws Exception {
        try (RaftNode follower = new RaftNode("n2", dir, new MemoryBackend())) {
            assertTrue(append(follower, 1, 1, 0, 0, batch("a")).accepted());
            // Escrita do líder anterior que nunca se confirmou: o líder novo tem outra no índice 2.
            assertTrue(append(follower, 1, 2, 1, 1, batch("sobra")).accepted());

            heartbeat(follower, 3, 2);

            assertEquals(1, follower.commitIndex());
            assertTrue(follower.stateMachine().get("sobra").isEmpty());
            assertTrue(append(follower, 3, 2, 1, 2, batch("certa")).accepted());
            assertEquals(2, follower.commitIndex());
            assertTrue(follower.stateMachine().get("certa").isPresent());
        }
    }

    @Test
    @DisplayName("uma entrada reenviada não confirma o que vem depois dela sem conferir")
    void resentEntryDoesNotCommitWhatFollows() throws Exception {
        try (RaftNode follower = new RaftNode("n2", dir, new MemoryBackend())) {
            assertTrue(append(follower, 3, 1, 0, 0, batch("a")).accepted());
            assertTrue(append(follower, 3, 2, 3, 0, batch("b")).accepted());
            assertTrue(append(follower, 3, 3, 3, 0, batch("sem-quorum")).accepted());

            // o líder reenvia a entrada 2 já com o índice 3 confirmado, mas o 3 dele é outro
            assertTrue(append(follower, 3, 2, 3, 3, batch("b")).accepted());

            assertEquals(2, follower.commitIndex());
            assertTrue(append(follower, 3, 3, 3, 3, batch("certa")).accepted());
            assertTrue(follower.stateMachine().get("certa").isPresent());
            assertTrue(follower.stateMachine().get("sem-quorum").isEmpty());
        }
    }

    @Test
    @DisplayName("entrada que o líder antigo reescreveu no mesmo mandato é trocada")
    void rewrittenEntryReplacesTheStoredOne() throws Exception {
        try (RaftNode follower = new RaftNode("n2", dir, new MemoryBackend())) {
            assertTrue(append(follower, 3, 1, 0, 0, batch("a")).accepted());
            assertTrue(append(follower, 3, 2, 3, 1, batch("sem-quorum")).accepted());

            // o líder apagou a escrita que não confirmou e gravou outra no mesmo índice
            assertTrue(append(follower, 3, 2, 3, 1, batch("confirmada")).accepted());

            heartbeat(follower, 3, 2);
            assertTrue(follower.stateMachine().get("confirmada").isPresent());
            assertTrue(follower.stateMachine().get("sem-quorum").isEmpty());
        }
    }

    @Test
    @DisplayName("entrada gravada por outro mandato continua sendo recusada quando o termo não confere")
    void entryFromAnotherLeaderIsStillRejected() throws Exception {
        try (RaftNode follower = new RaftNode("n2", dir, new MemoryBackend())) {
            assertTrue(append(follower, 1, 1, 0, 0, batch("a")).accepted());
            assertTrue(append(follower, 2, 2, 1, 1, batch("outro")).accepted());

            // O líder do mandato 3 guarda outra entrada 2, do mandato 1: a do seguidor não veio dele.
            assertFalse(append(follower, 3, 3, 1, 1, batch("c")).accepted());
            assertTrue(append(follower, 3, 2, 1, 1, batch("certo")).accepted());
            assertTrue(replicate(follower, 3, List.of(1L, 1L, 3L),
                List.of(batch("a"), batch("certo"), batch("c")), 3, 1));

            heartbeat(follower, 3, 3);
            assertTrue(follower.stateMachine().get("certo").isPresent());
            assertTrue(follower.stateMachine().get("outro").isEmpty());
        }
    }
}
