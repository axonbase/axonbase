package com.axonbase.core.storage;

import com.axonbase.core.hlc.HlcTimestamp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Teto gravado do carimbo e do índice")
class CeilingRecordTest {

    private static final long LEAD = 200;
    private static final long BLOCK = 1_000;

    private final List<CommitWatermark> disk = new ArrayList<>();
    private boolean fail;
    private final CeilingRecord ceiling = new CeilingRecord(raw -> {
        if (fail) {
            throw new IllegalStateException("disco cheio");
        }
        disk.add(CommitSequencer.decode(raw));
    }, LEAD, BLOCK);

    private CommitWatermark onDisk() {
        return disk.get(disk.size() - 1);
    }

    @Test
    @DisplayName("subir o carimbo grava com folga à frente, e pedido já coberto não grava de novo")
    void raisingWritesAheadOnce() {
        ceiling.raise(10_000);
        ceiling.raise(10_100);

        assertEquals(1, disk.size());
        assertEquals(10_200, onDisk().stamp().physical());
        assertEquals(10_200, ceiling.durableMillis());
    }

    @Test
    @DisplayName("subir o índice grava um bloco à frente e nunca recua o carimbo")
    void raisingTheIndexReservesABlock() {
        ceiling.raise(10_000);
        ceiling.raiseIndex(5);

        assertEquals(5 + BLOCK, ceiling.durableIndex());
        assertEquals(10_200, onDisk().stamp().physical());
        assertEquals(5 + BLOCK, onDisk().index());
    }

    @Test
    @DisplayName("falha na gravação não muda o teto em memória: nada acima dele pode ser usado")
    void aFailedWriteLeavesTheCeilingWhereItWas() {
        ceiling.raise(10_000);
        fail = true;

        assertThrows(IllegalStateException.class, () -> ceiling.raise(20_000));
        assertThrows(IllegalStateException.class, () -> ceiling.raiseIndex(99_999));

        assertEquals(10_200, ceiling.durableMillis());
        assertEquals(0, ceiling.durableIndex());
    }

    @Test
    @DisplayName("pede renovação quando sobra menos da metade da folga, e só uma de cada vez resolve")
    void renewsBeforeRunningOut() {
        ceiling.raise(10_000);
        ceiling.raiseIndex(1);

        assertFalse(ceiling.needsRenewal(10_100, 1));
        assertTrue(ceiling.needsRenewal(10_101, 1));
        assertTrue(ceiling.needsRenewal(10_000, BLOCK / 2 + 2));

        ceiling.renew(10_101, 1);
        assertEquals(10_301, ceiling.durableMillis());
        assertFalse(ceiling.needsRenewal(10_101, 1));
    }

    @Test
    @DisplayName("no desligamento o teto desce ao exato último carimbo e índice")
    void loweringWritesTheExactPosition() {
        ceiling.raise(10_000);

        ceiling.lower(10_003, 7);

        assertEquals(10_003, ceiling.durableMillis());
        assertEquals(7, ceiling.durableIndex());
        assertEquals(new HlcTimestamp(10_003, Integer.MAX_VALUE), onDisk().stamp());
        assertEquals(7, onDisk().index());
    }

    @Test
    @DisplayName("falha ao descer o teto não deixa a memória acima do disco")
    void aFailedLoweringStaysSafe() {
        ceiling.raise(10_000);
        fail = true;

        assertThrows(IllegalStateException.class, () -> ceiling.lower(10_003, 7));

        assertTrue(ceiling.durableMillis() <= 10_003);
        assertTrue(ceiling.durableIndex() <= 7);
    }

    @Test
    @DisplayName("com a base fechada nada mais é gravado")
    void aClosedCeilingRefusesToWrite() {
        ceiling.raise(10_000);
        ceiling.close(() -> { });

        assertThrows(IllegalStateException.class, () -> ceiling.raise(20_000));
        assertTrue(ceiling.closed());
        assertEquals(1, disk.size());
    }
}
