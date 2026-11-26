package com.axonbase.core.journal;

import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.storage.CommitWatermark;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.CRC32C;

/**
 * Formato de um segmento do journal (versão 1). O mesmo serve ao nó único e ao cluster.
 *
 * <pre>
 * cabeçalho  int MAGIC ("AXJ1"), short VERSION, long linha do tempo
 * registro   int tamanho (do tipo mais o corpo), byte tipo, corpo, int CRC32C do tipo mais o corpo
 *   lote     long índice, long físico, int contador, int nPuts, (int, chave, int, valor)*, int nDels, (int, chave)*
 *   rodapé   marca d'água, faixa de carimbos e índices, contagem, buracos, indicador de recuperado
 * fim        int tamanho do registro de rodapé inteiro, int FOOTER ("AXJE")
 * </pre>
 *
 * <p>As chaves levam o tamanho em 32 bits: o limite de 64 KB do {@code writeUTF} não vale aqui.</p>
 */
final class SegmentFormat {

    static final int MAGIC = 0x41584A31;
    static final int FOOTER = 0x41584A45;
    static final short VERSION = 1;
    static final int HEADER_BYTES = 14;
    static final int FOOTER_BYTES = 8;
    static final byte BATCH = 1;
    static final byte TRAILER = 2;
    /** Acima disto o tamanho lido é lixo, não um registro. */
    static final int MAX_RECORD = 1 << 30;

    /** O que o rodapé guarda sobre o segmento. */
    record Trailer(CommitWatermark complete, HlcTimestamp firstStamp, HlcTimestamp lastStamp, long firstIndex,
                   long lastIndex, long batches, List<IndexRange> holes, boolean recovered) {
    }

    private SegmentFormat() {
    }

    static byte[] header(long timeline) {
        return ByteBuffer.allocate(HEADER_BYTES).putInt(MAGIC).putShort(VERSION).putLong(timeline).array();
    }

    /** Tipo, índice, carimbo e as duas contagens. */
    private static final int BATCH_FIXED_BYTES = 1 + 8 + 12 + 4 + 4;

    /**
     * O registro completo de um lote (tamanho, tipo, corpo e CRC), montado num vetor do tamanho exato. Roda dentro do
     * commit: só as chaves viram bytes antes, e cada valor é copiado uma única vez.
     */
    static byte[] batchRecord(long index, HlcTimestamp stamp, Map<String, byte[]> puts, Set<String> deletes) {
        byte[][] putKeys = new byte[puts.size()][];
        byte[][] values = new byte[putKeys.length][];
        long covered = BATCH_FIXED_BYTES;
        int i = 0;
        for (var put : puts.entrySet()) {
            putKeys[i] = put.getKey().getBytes(StandardCharsets.UTF_8);
            values[i] = put.getValue();
            covered += 8L + putKeys[i].length + values[i].length;
            i++;
        }
        byte[][] deleteKeys = new byte[deletes.size()][];
        i = 0;
        for (String key : deletes) {
            deleteKeys[i] = key.getBytes(StandardCharsets.UTF_8);
            covered += 4L + deleteKeys[i].length;
            i++;
        }
        int length = Math.toIntExact(covered);
        byte[] record = new byte[Math.addExact(length, 8)];
        ByteBuffer out = ByteBuffer.wrap(record);
        out.putInt(length);
        int start = out.position();
        out.put(BATCH).putLong(index).putLong(stamp.physical()).putInt(stamp.logical());
        out.putInt(putKeys.length);
        for (int k = 0; k < putKeys.length; k++) {
            out.putInt(putKeys[k].length).put(putKeys[k]).putInt(values[k].length).put(values[k]);
        }
        out.putInt(deleteKeys.length);
        for (byte[] key : deleteKeys) {
            out.putInt(key.length).put(key);
        }
        CRC32C crc = new CRC32C();
        crc.update(record, start, length);
        out.putInt((int) crc.getValue());
        return record;
    }

    /** Registro de rodapé seguido dos bytes finais do arquivo. */
    static byte[] trailerAndFooter(Trailer t) {
        byte[] record = record(TRAILER, out -> {
            writeStamp(out, t.complete().stamp());
            out.writeLong(t.complete().index());
            writeStamp(out, t.firstStamp());
            writeStamp(out, t.lastStamp());
            out.writeLong(t.firstIndex());
            out.writeLong(t.lastIndex());
            out.writeLong(t.batches());
            out.writeInt(t.holes().size());
            for (IndexRange hole : t.holes()) {
                out.writeLong(hole.from());
                out.writeLong(hole.to());
            }
            out.writeBoolean(t.recovered());
        });
        return ByteBuffer.allocate(record.length + FOOTER_BYTES).put(record).putInt(record.length).putInt(FOOTER)
            .array();
    }

    static JournalRecord decodeBatch(DataInputStream in) throws IOException {
        long index = in.readLong();
        HlcTimestamp stamp = readStamp(in);
        int puts = count(in);
        Map<String, byte[]> values = new LinkedHashMap<>();
        for (int i = 0; i < puts; i++) {
            String key = new String(readBytes(in), StandardCharsets.UTF_8);
            values.put(key, readBytes(in));
        }
        int dels = count(in);
        Set<String> deletes = new LinkedHashSet<>();
        for (int i = 0; i < dels; i++) {
            deletes.add(new String(readBytes(in), StandardCharsets.UTF_8));
        }
        return new JournalRecord(index, new CommittedBatch("", values, deletes, false, stamp));
    }

    static Trailer decodeTrailer(DataInputStream in) throws IOException {
        CommitWatermark complete = new CommitWatermark(readStamp(in), in.readLong());
        HlcTimestamp firstStamp = readStamp(in);
        HlcTimestamp lastStamp = readStamp(in);
        long firstIndex = in.readLong();
        long lastIndex = in.readLong();
        long batches = in.readLong();
        int n = count(in);
        List<IndexRange> holes = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            holes.add(new IndexRange(in.readLong(), in.readLong()));
        }
        return new Trailer(complete, firstStamp, lastStamp, firstIndex, lastIndex, batches, holes, in.readBoolean());
    }

    static int crc(byte[] typeAndBody) {
        CRC32C crc = new CRC32C();
        crc.update(typeAndBody);
        return (int) crc.getValue();
    }

    @FunctionalInterface
    private interface Body {
        void write(DataOutputStream out) throws IOException;
    }

    private static byte[] record(byte type, Body body) {
        try (var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes)) {
            out.writeByte(type);
            body.write(out);
            out.flush();
            byte[] typeAndBody = bytes.toByteArray();
            return ByteBuffer.allocate(typeAndBody.length + 8).putInt(typeAndBody.length).put(typeAndBody)
                .putInt(crc(typeAndBody)).array();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void writeStamp(DataOutputStream out, HlcTimestamp stamp) throws IOException {
        out.writeLong(stamp.physical());
        out.writeInt(stamp.logical());
    }

    private static HlcTimestamp readStamp(DataInputStream in) throws IOException {
        return new HlcTimestamp(in.readLong(), in.readInt());
    }

    private static int count(DataInputStream in) throws IOException {
        int n = in.readInt();
        if (n < 0 || n > in.available()) {
            throw new IOException("contagem inválida no registro: " + n);
        }
        return n;
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int n = count(in);
        byte[] out = in.readNBytes(n);
        if (out.length != n) {
            throw new IOException("registro cortado");
        }
        return out;
    }
}
