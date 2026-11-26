package com.axonbase.core.storage;

import com.axonbase.core.hlc.HlcTimestamp;

/**
 * Posição na sequência de commits: um carimbo, um índice e a linha do tempo. Como marca d'água, diz que todos os
 * lotes com carimbo menor ou igual a {@code stamp} já foram gravados e entregues, e que o índice deles não passa de
 * {@code index}.
 *
 * <p>{@code timeline} identifica uma história contínua da base: muda depois de uma queda, de uma troca de base ou de
 * escritas feitas sem carimbo. Posições de linhas do tempo diferentes não se comparam pelo índice.</p>
 */
public record CommitWatermark(HlcTimestamp stamp, long index, long timeline) {

    public static final CommitWatermark NONE = new CommitWatermark(HlcTimestamp.NONE, 0L);

    /** Sem linha do tempo conhecida. */
    public CommitWatermark(HlcTimestamp stamp, long index) {
        this(stamp, index, 0L);
    }
}
