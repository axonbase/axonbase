package com.axonbase.core.journal;

import com.axonbase.core.hlc.HlcTimestamp;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Os segmentos de um diretório de journal: listar, ler, importar de fora e recuperar o que ficou aberto numa
 * queda. Todo nome vindo de fora é validado.
 */
public final class JournalSegments {

    private static final Pattern NAME = Pattern.compile("seg-\\d{15}-\\d{10}\\.axj");
    private static final Pattern SOURCE = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final String OPEN_SUFFIX = ".open";

    private JournalSegments() {
    }

    /** O nome do segmento criado no carimbo dado: a ordem dos nomes é a ordem dos carimbos. */
    static String nameFor(HlcTimestamp created) {
        return String.format("seg-%015d-%010d.axj", created.physical(), created.logical());
    }

    static String openName(String name) {
        return name + OPEN_SUFFIX;
    }

    /** O diretório do journal a usar: o próprio ({@code source} nulo) ou a área de importação da origem. */
    public static Path importRoot(Path journalDir, String source) {
        if (source == null) {
            return journalDir;
        }
        if (!SOURCE.matcher(source).matches() || source.equals(".") || source.equals("..")) {
            throw new IllegalArgumentException("origem inválida: " + source);
        }
        return journalDir.resolve("imports").resolve(source);
    }

    /** O arquivo do segmento {@code name}; recusa qualquer nome fora do padrão. */
    public static Path resolve(Path journalDir, String source, String name) {
        if (name == null || !NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("nome de segmento inválido: " + name);
        }
        return importRoot(journalDir, source).resolve(name);
    }

    /**
     * Os segmentos fechados do diretório, em ordem de escrita. Um arquivo ilegível fica de fora, com aviso: os
     * lotes dele aparecem como índices faltando, e a restauração não passa por esse ponto.
     */
    public static List<SegmentInfo> list(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(directory)) {
            files = s.filter(p -> NAME.matcher(p.getFileName().toString()).matches())
                .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        }
        List<SegmentInfo> out = new ArrayList<>(files.size());
        for (Path file : files) {
            try {
                out.add(info(file));
            } catch (java.nio.file.NoSuchFileException removed) {
                // apagado pela retenção entre a listagem e a leitura
            } catch (IOException unreadable) {
                System.err.println("{\"event\":\"pitr_segment_unreadable\",\"segment\":\"" + file.getFileName()
                    + "\",\"error\":\"" + unreadable.getMessage() + "\"}");
            }
        }
        return out;
    }

    /** O índice de um segmento fechado, lido do rodapé (sem percorrer os lotes). */
    public static SegmentInfo info(Path file) throws IOException {
        try (RandomAccessFile raw = new RandomAccessFile(file.toFile(), "r")) {
            long size = raw.length();
            if (size < SegmentFormat.HEADER_BYTES + SegmentFormat.FOOTER_BYTES) {
                throw new IOException("segmento incompleto: " + file.getFileName());
            }
            checkHeader(raw.readInt(), raw.readShort(), file);
            raw.seek(size - SegmentFormat.FOOTER_BYTES);
            int length = raw.readInt();
            if (raw.readInt() != SegmentFormat.FOOTER || length < 9
                || length > size - SegmentFormat.HEADER_BYTES - SegmentFormat.FOOTER_BYTES) {
                throw new IOException("rodapé inválido: " + file.getFileName());
            }
            raw.seek(size - SegmentFormat.FOOTER_BYTES - length);
            byte[] record = new byte[length];
            raw.readFully(record);
            byte[] typeAndBody = checkRecord(new DataInputStream(new ByteArrayInputStream(record)), file);
            if (typeAndBody == null || typeAndBody[0] != SegmentFormat.TRAILER) {
                throw new IOException("rodapé inválido: " + file.getFileName());
            }
            return toInfo(file.getFileName().toString(), size, SegmentFormat.decodeTrailer(body(typeAndBody)));
        }
    }

    static SegmentInfo toInfo(String name, long size, SegmentFormat.Trailer t) {
        return new SegmentInfo(name, size, t.batches(), t.firstStamp(), t.lastStamp(), t.firstIndex(), t.lastIndex(),
            t.complete(), t.holes(), t.recovered());
    }

    /**
     * Percorre os lotes de um segmento fechado, na ordem em que foram escritos, até {@code visitor} devolver
     * {@code false}. Um registro alterado, cortado ou um segmento sem rodapé lançam {@link IOException}.
     */
    public static void read(Path file, Predicate<JournalRecord> visitor) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            checkHeader(in.readInt(), in.readShort(), file);
            while (true) {
                byte[] typeAndBody = checkRecord(in, file);
                if (typeAndBody == null) {
                    throw new IOException("segmento sem rodapé: " + file.getFileName());
                }
                if (typeAndBody[0] == SegmentFormat.TRAILER) {
                    return;
                }
                if (typeAndBody[0] != SegmentFormat.BATCH) {
                    throw new IOException("registro desconhecido em " + file.getFileName());
                }
                if (!visitor.test(SegmentFormat.decodeBatch(body(typeAndBody)))) {
                    return;
                }
            }
        }
    }

    /**
     * Grava um segmento recebido de fora na área de importação da origem, de forma atômica. O corpo é validado
     * (todos os checksums e o rodapé contra os lotes) antes de aparecer com o nome final.
     *
     * @throws IllegalArgumentException conteúdo ou nome inválido
     * @throws IOException              tamanho diferente do declarado ou falha de disco
     */
    public static SegmentInfo write(Path journalDir, String source, String name, InputStream body, long declaredSize)
            throws IOException {
        if (source == null) {
            throw new IllegalArgumentException("origem obrigatória na gravação");
        }
        Path target = resolve(journalDir, source, name);
        Files.createDirectories(target.getParent());
        Path tmp = Files.createTempFile(target.getParent(), ".incoming-", ".tmp");
        try {
            long written;
            try (OutputStream out = Files.newOutputStream(tmp)) {
                written = body.transferTo(out);
            }
            if (declaredSize >= 0 && written != declaredSize) {
                throw new IOException("tamanho recebido " + written + " difere do declarado " + declaredSize);
            }
            SegmentInfo info = validate(tmp);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return toInfo(name, info.sizeBytes(), new SegmentFormat.Trailer(info.complete(), info.firstStamp(),
                info.lastStamp(), info.firstIndex(), info.lastIndex(), info.batches(), info.holes(),
                info.recovered()));
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static SegmentInfo validate(Path file) {
        try {
            SegmentInfo info = info(file);
            SegmentWriter.Stats stats = new SegmentWriter.Stats();
            read(file, record -> {
                stats.add(record.index(), record.batch().stamp());
                return true;
            });
            SegmentFormat.Trailer expected = stats.trailer(info.complete(), info.recovered());
            if (expected.batches() != info.batches() || expected.firstIndex() != info.firstIndex()
                || expected.lastIndex() != info.lastIndex() || !expected.holes().equals(info.holes())
                || !expected.lastStamp().equals(info.lastStamp())) {
                throw new IOException("o rodapé não confere com os lotes");
            }
            return info;
        } catch (IOException e) {
            throw new IllegalArgumentException("segmento inválido: " + e.getMessage(), e);
        }
    }

    /**
     * Fecha os segmentos que ficaram abertos numa queda: corta no último lote inteiro e grava um rodapé que não
     * promete cobertura além da do segmento anterior. Um segmento sem nenhum lote inteiro é apagado.
     */
    static void recover(Path directory) throws IOException {
        List<Path> open;
        try (Stream<Path> s = Files.list(directory)) {
            open = s.filter(p -> p.getFileName().toString().endsWith(OPEN_SUFFIX))
                .sorted(Comparator.comparing(p -> p.getFileName().toString())).toList();
        }
        for (Path file : open) {
            String fileName = file.getFileName().toString();
            String name = fileName.substring(0, fileName.length() - OPEN_SUFFIX.length());
            if (!NAME.matcher(name).matches() || Files.exists(directory.resolve(name))) {
                Files.delete(file);
                continue;
            }
            SegmentWriter.Stats stats = new SegmentWriter.Stats();
            long end = validPrefix(file, stats);
            if (stats.count() == 0) {
                Files.delete(file);
                continue;
            }
            var previous = list(directory).stream().filter(i -> i.name().compareTo(name) < 0)
                .reduce((a, b) -> b).map(SegmentInfo::complete)
                .orElse(com.axonbase.core.storage.CommitWatermark.NONE);
            SegmentFormat.Trailer trailer = stats.trailer(previous, true);
            byte[] tail = SegmentFormat.trailerAndFooter(trailer);
            try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                channel.truncate(end);
                channel.position(end);
                ByteBuffer buffer = ByteBuffer.wrap(tail);
                while (buffer.hasRemaining()) {
                    channel.write(buffer);
                }
                channel.force(true);
            }
            SegmentWriter.publish(directory, file, name, end + tail.length, trailer);
        }
    }

    /** Onde termina o último lote inteiro do arquivo aberto, contando os lotes em {@code stats}. */
    private static long validPrefix(Path file, SegmentWriter.Stats stats) throws IOException {
        long end = 0;
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            checkHeader(in.readInt(), in.readShort(), file);
            end = SegmentFormat.HEADER_BYTES;
            while (true) {
                byte[] typeAndBody = checkRecord(in, file);
                if (typeAndBody == null || typeAndBody[0] != SegmentFormat.BATCH) {
                    return end;
                }
                JournalRecord record = SegmentFormat.decodeBatch(body(typeAndBody));
                stats.add(record.index(), record.batch().stamp());
                end += typeAndBody.length + 8L;
            }
        } catch (IOException cutShort) {
            // cauda cortada ou ilegível: vale o que veio antes dela
            return end;
        }
    }

    private static void checkHeader(int magic, short version, Path file) throws IOException {
        if (magic != SegmentFormat.MAGIC) {
            throw new IOException("não é um segmento de journal: " + file.getFileName());
        }
        if (version != SegmentFormat.VERSION) {
            throw new IOException("versão de segmento não suportada: " + version);
        }
    }

    /** O tipo mais o corpo do próximo registro, com o CRC conferido; {@code null} no fim do arquivo. */
    private static byte[] checkRecord(DataInputStream in, Path file) throws IOException {
        int length;
        try {
            length = in.readInt();
        } catch (EOFException end) {
            return null;
        }
        if (length < 1 || length > SegmentFormat.MAX_RECORD) {
            throw new IOException("registro corrompido em " + file.getFileName());
        }
        byte[] typeAndBody = in.readNBytes(length);
        if (typeAndBody.length != length || in.readInt() != SegmentFormat.crc(typeAndBody)) {
            throw new IOException("registro corrompido em " + file.getFileName());
        }
        return typeAndBody;
    }

    private static DataInputStream body(byte[] typeAndBody) {
        return new DataInputStream(new ByteArrayInputStream(Arrays.copyOfRange(typeAndBody, 1, typeAndBody.length)));
    }
}
