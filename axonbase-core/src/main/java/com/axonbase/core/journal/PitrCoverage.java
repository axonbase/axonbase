package com.axonbase.core.journal;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.storage.CommitWatermark;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Até onde dá para restaurar a partir de um backup. Olha o índice dos segmentos e só lê o conteúdo dos poucos que o
 * ponto de parada corta ao meio.
 *
 * <p>Como o índice dos lotes não tem buracos e cresce junto com o carimbo, partindo do que o backup já contém basta
 * achar o maior índice {@code K} sem falhas no journal: todo instante até o carimbo de {@code K} (ou até uma marca
 * d'água que não passe de {@code K}) está completo.</p>
 */
public final class PitrCoverage {

    /**
     * @param from           carimbo do backup; instantes anteriores precisam de um backup mais antigo
     * @param to             último instante com todos os lotes presentes
     * @param contiguousIndex maior índice sem falhas a partir do backup
     */
    public record Window(HlcTimestamp from, HlcTimestamp to, long contiguousIndex) {

        /** O último milissegundo que se pode pedir: o de {@code to} só vale quando está inteiro coberto. */
        public long latestMillis() {
            return latestMillisUpTo(to);
        }
    }

    /**
     * O último milissegundo inteiro coberto quando se conhece tudo até {@code stamp}: restaurar a um instante inclui
     * todos os carimbos daquele milissegundo.
     */
    public static long latestMillisUpTo(HlcTimestamp stamp) {
        return stamp.logical() == Integer.MAX_VALUE ? stamp.physical() : stamp.physical() - 1;
    }

    private PitrCoverage() {
    }

    /**
     * Os segmentos que podem continuar a história do backup: mesma linha do tempo e, nos índices posteriores a ele,
     * carimbos também posteriores (numa história contínua o índice e o carimbo crescem juntos).
     */
    static List<SegmentInfo> continuing(List<SegmentInfo> segments, CommitWatermark base) {
        return segments.stream()
            .filter(s -> s.timeline() == base.timeline())
            .filter(s -> s.batches() == 0 || s.lastIndex() <= base.index()
                || s.lastStamp().compareTo(base.stamp()) > 0)
            .toList();
    }

    public static Window window(Path directory, List<SegmentInfo> all, CommitWatermark base) {
        List<SegmentInfo> segments = continuing(all, base);
        List<IndexRange> present = new ArrayList<>();
        for (SegmentInfo s : segments) {
            if (s.batches() == 0) {
                continue;
            }
            long from = s.firstIndex();
            for (IndexRange hole : s.holes()) {
                present.add(new IndexRange(from, hole.from() - 1));
                from = hole.to() + 1;
            }
            present.add(new IndexRange(from, s.lastIndex()));
        }
        present.sort(Comparator.comparingLong(IndexRange::from));
        long contiguous = base.index();
        for (IndexRange range : present) {
            if (range.from() > contiguous + 1) {
                break;
            }
            contiguous = Math.max(contiguous, range.to());
        }
        HlcTimestamp to = base.stamp();
        for (SegmentInfo s : segments) {
            if (s.batches() > 0 && s.lastIndex() <= contiguous) {
                to = HlcTimestamp.max(to, s.lastStamp());
            } else if (s.batches() > 0 && s.firstIndex() <= contiguous && contiguous > base.index()) {
                // o ponto de parada cai dentro deste segmento: vale o carimbo do último lote antes dele
                to = HlcTimestamp.max(to, newestUpTo(directory.resolve(s.name()), contiguous));
            }
            if (s.complete().index() <= contiguous) {
                to = HlcTimestamp.max(to, s.complete().stamp());
            }
        }
        return new Window(base.stamp(), to, contiguous);
    }

    /** O maior carimbo entre os lotes do segmento com índice até {@code index}. */
    private static HlcTimestamp newestUpTo(Path segment, long index) {
        HlcTimestamp[] newest = {HlcTimestamp.NONE};
        try {
            JournalSegments.read(segment, record -> {
                if (record.index() <= index) {
                    newest[0] = HlcTimestamp.max(newest[0], record.batch().stamp());
                }
                return true;
            });
        } catch (IOException unreadable) {
            // um trecho ilegível não amplia a janela; a reaplicação acusaria o defeito
            return HlcTimestamp.NONE;
        }
        return newest[0];
    }
}
