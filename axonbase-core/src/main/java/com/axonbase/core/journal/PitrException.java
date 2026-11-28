package com.axonbase.core.journal;

import com.axonbase.core.hlc.HlcTimestamp;

/** A restauração ao instante pedido não é possível com este backup e este journal. A base ativa não foi tocada. */
public final class PitrException extends RuntimeException {

    public enum Reason {
        /** O backup não existe. */
        NO_BACKUP,
        /** O backup não tem marca de carimbo (anterior à 0.11): não serve de base. */
        NO_BASE_STAMP,
        /** O backup já contém lotes posteriores ao instante: use um backup mais antigo. */
        BASE_TOO_NEW,
        /** O journal não cobre o instante: falta um lote, ou ele ainda não foi arquivado. */
        UNREACHABLE,
        /** Um segmento do journal está ilegível ou corrompido. */
        BAD_JOURNAL
    }

    private final Reason reason;
    private final transient HlcTimestamp latest;

    public PitrException(Reason reason, String message, HlcTimestamp latest, Throwable cause) {
        super(message, cause);
        this.reason = reason;
        this.latest = latest;
    }

    public PitrException(Reason reason, String message) {
        this(reason, message, HlcTimestamp.NONE, null);
    }

    public Reason reason() {
        return reason;
    }

    /** Em {@link Reason#UNREACHABLE}, o último instante alcançável com este backup. */
    public HlcTimestamp latest() {
        return latest;
    }
}
