package com.axonbase.core.storage;

import com.axonbase.core.hlc.HlcTimestamp;

/**
 * Posição na sequência de commits: um carimbo e um índice. Como marca d'água, diz que todos os lotes com carimbo
 * menor ou igual a {@code stamp} já foram gravados e entregues, e que o índice deles não passa de {@code index}.
 */
public record CommitWatermark(HlcTimestamp stamp, long index) {

    public static final CommitWatermark NONE = new CommitWatermark(HlcTimestamp.NONE, 0L);
}
