package com.axonbase.core.engine;

import com.axonbase.common.Messages;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;

/**
 * Formato {@code .axbak} v2: segmentos com manifesto ({@code !meta}), contagens e SHA-256 ({@code !end}).
 * O SHA-256 cobre os bytes do segmento do início do cabeçalho até a quebra de linha da última linha de
 * dados, imediatamente antes da linha {@code !end}. Um restore aceita um ou mais segmentos concatenados.
 */
public final class BackupFormat {

    public static final String HEADER_V2 = "--axonbase-backup-v2--";
    public static final String HEADER_V1 = "--axonbase-backup-v1--";
    static final String META = "!meta";
    static final String DEL = "!del";
    static final String END = "!end";
    static final String CTRL = "ctrl";

    private BackupFormat() {
    }

    /** Manifesto e contagens de um segmento já validado. */
    public record Segment(String type, long since, long version, long puts, long deletes) {
        public boolean full() {
            return "full".equals(type);
        }
    }

    // ------------------------------------------------------------------ escrita

    /** Escreve um segmento em streaming, com memória constante e SHA-256 incremental. */
    public static final class SegmentWriter {
        private final OutputStream out;
        private final MessageDigest digest = sha256();
        private final byte[] buf = new byte[1 << 16];
        private int len;
        private long puts;
        private long deletes;
        private final Base64.Encoder b64 = Base64.getEncoder();

        public SegmentWriter(OutputStream out, boolean full, long since, long version, String nodeId,
                             String engineVersion) throws IOException {
            this.out = out;
            line(HEADER_V2);
            String meta = "{\"format\":2,\"type\":\"" + (full ? "full" : "incremental") + "\",\"since\":"
                + (full ? 0 : since) + ",\"version\":" + version + ",\"createdAt\":\"" + Instant.now()
                + "\",\"nodeId\":" + quote(nodeId) + ",\"engineVersion\":" + quote(engineVersion) + "}";
            line(META + "\t" + b64.encodeToString(meta.getBytes(StandardCharsets.UTF_8)));
        }

        public void ctrl(byte[] snapshot) throws IOException {
            line(CTRL + "\t" + b64.encodeToString(snapshot));
        }

        public void put(String key, byte[] value) throws IOException {
            line(key + "\t" + b64.encodeToString(value));
            puts++;
        }

        public void delete(String key) throws IOException {
            line(DEL + "\t" + b64.encodeToString(key.getBytes(StandardCharsets.UTF_8)));
            deletes++;
        }

        /** Fecha o segmento: grava a linha {@code !end} com contagens e checksum. */
        public void finish() throws IOException {
            drain();
            String end = "{\"puts\":" + puts + ",\"deletes\":" + deletes + ",\"sha256\":\""
                + HexFormat.of().formatHex(digest.digest()) + "\"}";
            out.write((END + "\t" + b64.encodeToString(end.getBytes(StandardCharsets.UTF_8)) + "\n")
                .getBytes(StandardCharsets.UTF_8));
            out.flush();
        }

        private void line(String text) throws IOException {
            byte[] raw = text.getBytes(StandardCharsets.UTF_8);
            if (len + raw.length + 1 > buf.length) {
                drain();
            }
            if (raw.length + 1 > buf.length) {
                digest.update(raw);
                digest.update((byte) '\n');
                out.write(raw);
                out.write('\n');
                return;
            }
            System.arraycopy(raw, 0, buf, len, raw.length);
            len += raw.length;
            buf[len++] = '\n';
        }

        private void drain() throws IOException {
            if (len > 0) {
                digest.update(buf, 0, len);
                out.write(buf, 0, len);
                len = 0;
            }
        }
    }

    // ------------------------------------------------------------------ leitura

    /** Leitor de linhas que informa se a linha terminou em quebra de linha (truncamento). */
    private static final class Lines implements AutoCloseable {
        private final InputStream in;
        private final java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
        boolean terminated;

        Lines(Path file) throws IOException {
            this.in = new BufferedInputStream(Files.newInputStream(file), 1 << 16);
        }

        /** {@code null} no fim do arquivo. */
        String next() throws IOException {
            acc.reset();
            int b;
            boolean any = false;
            terminated = false;
            while ((b = in.read()) >= 0) {
                any = true;
                if (b == '\n') {
                    terminated = true;
                    break;
                }
                acc.write(b);
            }
            return any ? acc.toString(StandardCharsets.UTF_8) : null;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }

    /** {@code true} se o arquivo começa com o cabeçalho v2. */
    public static boolean isV2(Path file) throws IOException {
        try (Lines lines = new Lines(file)) {
            return HEADER_V2.equals(lines.next());
        }
    }

    /**
     * Passo 1 do restore, sem tocar na base: valida cada segmento (cabeçalho, manifesto, contagens, checksum,
     * truncamento) e a continuidade da cadeia.
     */
    public static List<Segment> validate(Path file) throws IOException {
        List<Segment> segments = new ArrayList<>();
        try (Lines lines = new Lines(file)) {
            String line = lines.next();
            while (line != null) {
                if (!HEADER_V2.equals(line) || !lines.terminated) {
                    throw invalid(Messages.get("backup_segment_header_invalid"));
                }
                segments.add(readSegment(lines, segments.size() + 1));
                line = lines.next();
            }
        }
        if (segments.isEmpty()) {
            throw invalid(Messages.get("stmt_cluster_backup_invalid"));
        }
        if (!segments.get(0).full()) {
            throw new BackupFormatException(BackupFormatException.CHAIN_INVALID, 409,
                Messages.get("backup_chain_invalid"));
        }
        for (int i = 1; i < segments.size(); i++) {
            Segment prev = segments.get(i - 1);
            Segment cur = segments.get(i);
            if (cur.full() || cur.since() != prev.version()) {
                throw new BackupFormatException(BackupFormatException.CHAIN_INVALID, 409,
                    Messages.get("backup_chain_broken", i + 1, cur.since(), prev.version()));
            }
        }
        return segments;
    }

    private static Segment readSegment(Lines lines, int index) throws IOException {
        MessageDigest md = sha256();
        md.update((HEADER_V2 + "\n").getBytes(StandardCharsets.UTF_8));
        String metaLine = lines.next();
        if (metaLine == null || !lines.terminated) {
            throw truncated(index);
        }
        if (!metaLine.startsWith(META + "\t")) {
            throw invalid(Messages.get("backup_meta_missing", index));
        }
        md.update(metaLine.getBytes(StandardCharsets.UTF_8));
        md.update((byte) '\n');
        AxonValue meta = json(metaLine.substring(META.length() + 1), index);
        String type = str(meta, "type");
        long since = num(meta, "since");
        long version = num(meta, "version");
        if (num(meta, "format") != 2 || !("full".equals(type) || "incremental".equals(type))) {
            throw invalid(Messages.get("backup_meta_invalid", index));
        }
        long puts = 0;
        long deletes = 0;
        while (true) {
            String line = lines.next();
            if (line == null || HEADER_V2.equals(line)) {
                throw truncated(index);
            }
            if (line.startsWith(END + "\t")) {
                if (!lines.terminated) {
                    throw truncated(index);
                }
                AxonValue end = json(line.substring(END.length() + 1), index);
                String actual = HexFormat.of().formatHex(md.digest());
                if (!actual.equalsIgnoreCase(str(end, "sha256"))) {
                    throw new BackupFormatException(BackupFormatException.CHECKSUM, 400,
                        Messages.get("backup_checksum", index));
                }
                if (num(end, "puts") != puts || num(end, "deletes") != deletes) {
                    throw new BackupFormatException(BackupFormatException.CHECKSUM, 400,
                        Messages.get("backup_counts_mismatch", index));
                }
                return new Segment(type, since, version, puts, deletes);
            }
            if (!lines.terminated) {
                throw truncated(index);
            }
            md.update(line.getBytes(StandardCharsets.UTF_8));
            md.update((byte) '\n');
            if (line.isBlank()) {
                continue;
            }
            if (line.indexOf('\t') <= 0) {
                throw invalid(Messages.get("backup_line_invalid", index));
            }
            if (line.startsWith(DEL + "\t")) {
                deletes++;
            } else if (!line.startsWith(CTRL + "\t")) {
                puts++;
            }
        }
    }

    /** Aplicador das linhas decodificadas durante o restore. */
    public interface LineSink {
        void ctrl(byte[] snapshot) throws IOException;

        void put(String key, byte[] value) throws IOException;

        void delete(String key) throws IOException;
    }

    /** Passo 2: percorre os segmentos já validados em ordem, entregando as linhas ao {@code sink}. */
    public static void apply(Path file, LineSink sink) throws IOException {
        Base64.Decoder dec = Base64.getDecoder();
        try (Lines lines = new Lines(file)) {
            String line;
            while ((line = lines.next()) != null) {
                if (line.isBlank() || HEADER_V2.equals(line) || line.startsWith(META + "\t")
                    || line.startsWith(END + "\t")) {
                    continue;
                }
                int tab = line.indexOf('\t');
                String key = line.substring(0, tab);
                byte[] value = dec.decode(line.substring(tab + 1));
                if (CTRL.equals(key)) {
                    sink.ctrl(value);
                } else if (DEL.equals(key)) {
                    sink.delete(new String(value, StandardCharsets.UTF_8));
                } else {
                    sink.put(key, value);
                }
            }
        }
    }

    // ------------------------------------------------------------------ utilitários

    private static AxonValue json(String b64, int index) {
        try {
            return AxonJson.parseDocument(new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw invalid(Messages.get("backup_meta_invalid", index));
        }
    }

    private static String str(AxonValue obj, String field) {
        try {
            return obj.asObject().get(field).asString();
        } catch (RuntimeException e) {
            throw invalid(Messages.get("backup_meta_invalid", field));
        }
    }

    private static long num(AxonValue obj, String field) {
        try {
            return obj.asObject().get(field).asLong();
        } catch (RuntimeException e) {
            throw invalid(Messages.get("backup_meta_invalid", field));
        }
    }

    private static BackupFormatException invalid(String message) {
        return new BackupFormatException(BackupFormatException.INVALID, 400, message);
    }

    private static BackupFormatException truncated(int index) {
        return new BackupFormatException(BackupFormatException.TRUNCATED, 400,
            Messages.get("backup_truncated", index));
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        String v = s == null ? "" : s;
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '"' || c == '\\') {
                sb.append('\\').append(c);
            } else if (c < 0x20) {
                sb.append(String.format("\\u%04x", (int) c));
            } else {
                sb.append(c);
            }
        }
        return sb.append('"').toString();
    }
}
