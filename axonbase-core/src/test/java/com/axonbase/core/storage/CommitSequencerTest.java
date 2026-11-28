package com.axonbase.core.storage;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Reserva de versões, carimbos e índices")
class CommitSequencerTest {

    private final AtomicLong wall = new AtomicLong(10_000);
    private final AtomicBoolean fail = new AtomicBoolean();
    private final HybridClock clock = new HybridClock(wall::get, 500L, event -> { });
    private final CeilingRecord ceiling = new CeilingRecord(raw -> {
        if (fail.get()) {
            throw new IllegalStateException("disco cheio");
        }
    }, 200, 1_000);
    private final CommitSequencer sequencer = new CommitSequencer(clock, ceiling);

    CommitSequencerTest() {
        clock.guard(ceiling);
    }

    @Test
    @DisplayName("versões, carimbos e índices saem na mesma ordem, e as marcas d'água param no lote mais antigo")
    void reservationsKeepOneOrder() {
        CommitSequencer.Ticket first = sequencer.begin(3);
        CommitSequencer.Ticket second = sequencer.begin(1);
        CommitSequencer.Ticket third = sequencer.begin(2);

        assertEquals(1, first.firstVersion);
        assertEquals(4, second.firstVersion);
        assertEquals(5, third.firstVersion);
        assertTrue(second.stamp.compareTo(first.stamp) > 0 && third.stamp.compareTo(second.stamp) > 0);

        sequencer.end(second);
        assertEquals(0, sequencer.currentVersion());
        assertEquals(first.index - 1, sequencer.watermark().index());

        sequencer.end(first);
        assertEquals(4, sequencer.currentVersion());
        assertEquals(third.index - 1, sequencer.watermark().index());

        sequencer.end(third);
        assertEquals(6, sequencer.currentVersion());
        assertEquals(third.index, sequencer.watermark().index());
    }

    @Test
    @DisplayName("sem conseguir subir o teto, o lote falha mas a marca d'água continua respondendo")
    void theWatermarkNeverFails() {
        CommitSequencer.Ticket done = sequencer.begin(1);
        sequencer.end(done);
        fail.set(true);
        wall.addAndGet(60_000);

        assertThrows(IllegalStateException.class, () -> sequencer.begin(1));
        CommitWatermark mark = assertDoesNotThrow(sequencer::watermark);

        assertEquals(done.index, mark.index());
        assertEquals(done.stamp, mark.stamp());
        assertTrue(mark.stamp().compareTo(new HlcTimestamp(ceiling.durableMillis(), Integer.MAX_VALUE)) <= 0);
    }

    @Test
    @DisplayName("só os lotes que entram durante uma cópia registram a própria posição")
    void batchesRecordTheirPositionOnlyWhileCopying() {
        CommitSequencer.Ticket before = sequencer.begin(1);
        CommitWatermark upTo = sequencer.beginCopy();
        CommitSequencer.Ticket during = sequencer.begin(1);
        sequencer.endCopy();
        CommitSequencer.Ticket after = sequencer.begin(1);

        assertEquals(before.index, upTo.index());
        assertEquals(before.stamp, upTo.stamp());
        assertTrue(!before.recordPosition && during.recordPosition && !after.recordPosition);
    }
}
