package com.axonbase.core.storage;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;

import java.nio.ByteBuffer;

/**
 * O único monitor do caminho de commit. Dá a cada lote as versões, o carimbo HLC e um índice sem buracos, tudo na
 * mesma ordem, e lembra quais lotes ainda estão em gravação para calcular as marcas d'água.
 *
 * <p>Os lotes em gravação formam uma lista encadeada na ordem de entrada: como versões, carimbos e índices só crescem,
 * o primeiro da lista é o menor em todos eles, e entrar ou sair custa o mesmo com qualquer número de escritores.</p>
 */
final class CommitSequencer {

    /** Bytes de uma posição gravada: físico (8), contador (4) e índice (8). */
    private static final int ENCODED = 20;

    /** Um lote entre a reserva e o fim da entrega. */
    static final class Ticket {
        final long index;
        final HlcTimestamp stamp;
        /** A primeira das versões reservadas. */
        final long firstVersion;
        /** Há uma cópia física em curso: a posição do lote precisa ir junto dele para o disco. */
        final boolean recordPosition;
        /** O teto passou da metade da folga quando o lote entrou. */
        final boolean renewCeiling;
        private Ticket previous;
        private Ticket next;

        private Ticket(long index, HlcTimestamp stamp, long firstVersion, boolean recordPosition,
                       boolean renewCeiling) {
            this.index = index;
            this.stamp = stamp;
            this.firstVersion = firstVersion;
            this.recordPosition = recordPosition;
            this.renewCeiling = renewCeiling;
        }
    }

    /** O que fazer com o relógio e o conteúdo parados. */
    @FunctionalInterface
    interface AtRest {
        void accept(long clockMillis, CommitWatermark content);
    }

    private final HybridClock clock;
    private final CeilingRecord ceiling;
    private Ticket oldest;
    private Ticket newest;
    private long lastVersion;
    private long lastIndex;
    /** O maior carimbo que a base pode conter. */
    private HlcTimestamp content = HlcTimestamp.NONE;
    private long timeline;
    private int copies;

    CommitSequencer(HybridClock clock, CeilingRecord ceiling) {
        this.clock = clock;
        this.ceiling = ceiling;
    }

    /** Reserva {@code versions} versões consecutivas, um carimbo e um índice. */
    synchronized Ticket begin(int versions) {
        HlcTimestamp stamp = clock.now();
        long index = lastIndex + 1;
        if (index > ceiling.durableIndex()) {
            ceiling.raiseIndex(index);
        }
        lastIndex = index;
        content = stamp;
        return link(index, stamp, versions, ceiling.needsRenewal(stamp.physical(), index));
    }

    /** Como {@link #begin}, para um lote que já tem carimbo e índice (reaplicado do journal). */
    synchronized Ticket beginReplay(int versions, HlcTimestamp stamp, long index) {
        lastIndex = Math.max(lastIndex, index);
        content = HlcTimestamp.max(content, stamp);
        return link(index, stamp, versions, false);
    }

    private Ticket link(long index, HlcTimestamp stamp, int versions, boolean renew) {
        Ticket ticket = new Ticket(index, stamp, lastVersion + 1, copies > 0, renew);
        lastVersion += versions;
        if (newest == null) {
            oldest = ticket;
        } else {
            ticket.previous = newest;
            newest.next = ticket;
        }
        newest = ticket;
        return ticket;
    }

    synchronized void end(Ticket ticket) {
        if (ticket.previous == null) {
            oldest = ticket.next;
        } else {
            ticket.previous.next = ticket.next;
        }
        if (ticket.next == null) {
            newest = ticket.previous;
        } else {
            ticket.next.previous = ticket.previous;
        }
    }

    synchronized CommitWatermark watermark() {
        if (oldest == null) {
            return new CommitWatermark(idleStamp(), lastIndex, timeline);
        }
        return new CommitWatermark(oldest.stamp.predecessor(), oldest.index - 1, timeline);
    }

    /**
     * Um carimbo novo, para a marca d'água andar sem escritas. Sem poder subir o teto (base fechada, falha de disco)
     * vale o último já usado, que é uma marca mais antiga e igualmente verdadeira: pedir a marca nunca falha.
     */
    private HlcTimestamp idleStamp() {
        if (ceiling.closed()) {
            return clock.last();
        }
        try {
            return clock.now();
        } catch (RuntimeException e) {
            return clock.last();
        }
    }

    /** A marca d'água de versão: não passa de um lote ainda em gravação. */
    synchronized long currentVersion() {
        return oldest == null ? lastVersion : oldest.firstVersion - 1;
    }

    synchronized long lastVersion() {
        return lastVersion;
    }

    synchronized void versionsAtLeast(long version) {
        lastVersion = Math.max(lastVersion, version);
    }

    /**
     * Começa uma cópia física: os lotes que entrarem até {@link #endCopy} gravam a própria posição. Devolve o que a
     * base pode conter dos lotes anteriores.
     */
    synchronized CommitWatermark beginCopy() {
        copies++;
        return new CommitWatermark(content, lastIndex);
    }

    synchronized void endCopy() {
        copies--;
    }

    synchronized int copies() {
        return copies;
    }

    /** Roda {@code action} sem nenhum lote novo nem carimbo novo no meio. */
    synchronized void settle(AtRest action) {
        clock.settle(millis -> action.accept(millis, new CommitWatermark(content, lastIndex)));
    }

    synchronized void timeline(long id) {
        timeline = id;
    }

    synchronized long timeline() {
        return timeline;
    }

    synchronized long lastIndex() {
        return lastIndex;
    }

    /** Na abertura: de onde o índice continua e o que a base pode conter. */
    synchronized void resume(long index, HlcTimestamp contains) {
        lastIndex = Math.max(lastIndex, index);
        content = HlcTimestamp.max(content, contains);
    }

    /** A base pode conter carimbos até {@code stamp} (uma história nova começa ali). */
    synchronized void contains(HlcTimestamp stamp) {
        content = HlcTimestamp.max(content, stamp);
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
        if (raw == null || raw.length < ENCODED) {
            return CommitWatermark.NONE;
        }
        ByteBuffer in = ByteBuffer.wrap(raw);
        HlcTimestamp stamp = new HlcTimestamp(in.getLong(), in.getInt());
        return new CommitWatermark(stamp, in.getLong());
    }
}
