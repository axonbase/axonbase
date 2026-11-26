package com.axonbase.core.storage;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;

import java.nio.ByteBuffer;
import java.util.TreeMap;

/**
 * Dá a cada lote um carimbo HLC e um índice sem buracos, na mesma ordem, e lembra quais ainda estão em gravação
 * para calcular a marca d'água.
 */
final class CommitSequencer {

    /** Bytes gravados na chave {@code !hlc}: físico (8), contador (4) e índice (8). */
    private static final int ENCODED = 20;

    record Ticket(long index, HlcTimestamp stamp) {
    }

    private final HybridClock clock;
    private final TreeMap<Long, HlcTimestamp> inFlight = new TreeMap<>();
    private long lastIndex;
    private long timeline;

    CommitSequencer(HybridClock clock) {
        this.clock = clock;
    }

    synchronized Ticket begin() {
        HlcTimestamp stamp = clock.now();
        long index = ++lastIndex;
        inFlight.put(index, stamp);
        return new Ticket(index, stamp);
    }

    synchronized void end(Ticket ticket) {
        inFlight.remove(ticket.index());
    }

    synchronized CommitWatermark watermark() {
        if (inFlight.isEmpty()) {
            return new CommitWatermark(clock.now(), lastIndex, timeline);
        }
        var oldest = inFlight.firstEntry();
        return new CommitWatermark(oldest.getValue().predecessor(), oldest.getKey() - 1, timeline);
    }

    synchronized void timeline(long id) {
        timeline = id;
    }

    synchronized long lastIndex() {
        return lastIndex;
    }

    /** Continua a partir de uma posição já usada, sem nunca recuar o índice. */
    synchronized void advanceTo(long index) {
        lastIndex = Math.max(lastIndex, index);
    }

    /**
     * Pula um índice que nunca existirá, depois de {@code atLeast}: quem lê o journal não consegue atravessar esse
     * buraco, então não mistura lotes de duas histórias diferentes. Devolve o índice pulado.
     */
    synchronized long skipIndex(long atLeast) {
        lastIndex = Math.max(lastIndex, atLeast) + 1;
        return lastIndex;
    }

    static byte[] encode(HlcTimestamp stamp, long index) {
        return ByteBuffer.allocate(ENCODED).putLong(stamp.physical()).putInt(stamp.logical()).putLong(index).array();
    }

    static CommitWatermark decode(byte[] raw) {
        if (raw.length < ENCODED) {
            return CommitWatermark.NONE;
        }
        ByteBuffer in = ByteBuffer.wrap(raw);
        HlcTimestamp stamp = new HlcTimestamp(in.getLong(), in.getInt());
        return new CommitWatermark(stamp, in.getLong());
    }
}
