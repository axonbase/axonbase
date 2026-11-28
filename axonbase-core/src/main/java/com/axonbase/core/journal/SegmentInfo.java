package com.axonbase.core.journal;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.storage.CommitWatermark;

import java.util.List;

/**
 * O índice de um segmento fechado, lido do rodapé dele.
 *
 * @param name       nome do arquivo; a ordem dos nomes é a ordem de escrita
 * @param batches    quantos lotes há; zero num segmento de marca d'água
 * @param firstStamp menor carimbo ({@code NONE} se vazio)
 * @param lastStamp  maior carimbo
 * @param firstIndex menor índice (0 se vazio)
 * @param lastIndex  maior índice
 * @param complete   todos os lotes com carimbo até {@code complete.stamp} têm índice até {@code complete.index} e
 *                   estão neste segmento ou nos anteriores, salvo os que se perderam (aparecem como índice faltando)
 * @param holes      índices entre {@code firstIndex} e {@code lastIndex} que não estão neste segmento
 * @param recovered  fechado na abertura, depois de uma queda
 * @param timeline   história da base a que os lotes pertencem (veja {@link CommitWatermark#timeline()})
 */
public record SegmentInfo(String name, long sizeBytes, long batches, HlcTimestamp firstStamp,
                          HlcTimestamp lastStamp, long firstIndex, long lastIndex, CommitWatermark complete,
                          List<IndexRange> holes, boolean recovered, long timeline) {

    public SegmentInfo {
        holes = List.copyOf(holes);
    }
}
