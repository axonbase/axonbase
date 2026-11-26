package com.axonbase.core.partition.cold;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.function.BiPredicate;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * O objeto imutável de uma partição fria. No armazenamento ele vai comprimido; no cache, aberto e mapeado em memória.
 *
 * <pre>
 *   "AXCOLD1\n"
 *   repetido: int tamanhoDaChave, chave, int tamanhoDoValor, valor     (chaves em ordem crescente de bytes UTF-8)
 *   int -1, long quantidade
 *   SHA-256 de tudo que veio antes
 * </pre>
 */
public final class ColdObject {

    private static final byte[] MAGIC = "AXCOLD1\n".getBytes(StandardCharsets.US_ASCII);
    private static final int DIGEST_BYTES = 32;
    private static final int TRAILER_BYTES = 4 + 8 + DIGEST_BYTES;
    /** Uma chave a cada tantas fica em memória; a busca lê no máximo este trecho. */
    private static final int SAMPLE = 64;

    /** O objeto não está no formato, está cortado ou não confere com o resumo. */
    public static final class CorruptException extends IOException {
        CorruptException(String message) {
            super(message);
        }
    }

    private ColdObject() {
    }

    /** Escreve um objeto, comprimido, em {@code target}. */
    public static Writer writer(Path target) throws IOException {
        return new Writer(target);
    }

    public static final class Writer implements AutoCloseable {
        private final MessageDigest digest = sha256();
        private final OutputStream compressed;
        private final DataOutputStream out;
        private byte[] previous;
        private long rows;
        private long bytes;

        private Writer(Path target) throws IOException {
            compressed = new GZIPOutputStream(new BufferedOutputStream(Files.newOutputStream(target)), 64 * 1024);
            out = new DataOutputStream(new BufferedOutputStream(new DigestOutputStream(compressed, digest)));
            out.write(MAGIC);
        }

        /** Acrescenta uma entrada. As chaves precisam chegar em ordem crescente de bytes. */
        public void add(String key, byte[] value) throws IOException {
            byte[] raw = key.getBytes(StandardCharsets.UTF_8);
            if (previous != null && Arrays.compareUnsigned(previous, raw) >= 0) {
                throw new IllegalStateException("chaves fora de ordem no objeto frio: " + key);
            }
            previous = raw;
            out.writeInt(raw.length);
            out.write(raw);
            out.writeInt(value.length);
            out.write(value);
            rows++;
            bytes += 8L + raw.length + value.length;
        }

        public long rows() {
            return rows;
        }

        /** Bytes das entradas, sem compressão. */
        public long bytes() {
            return bytes;
        }

        /** Escreve o rodapé e fecha. */
        public void finish() throws IOException {
            out.writeInt(-1);
            out.writeLong(rows);
            out.flush();
            compressed.write(digest.digest());
            compressed.close();
        }

        @Override
        public void close() throws IOException {
            compressed.close();
        }
    }

    /** Descomprime o objeto baixado em {@code source} para {@code target}. */
    public static void inflate(Path source, Path target) throws IOException {
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(source)), 64 * 1024)) {
            Files.copy(in, target);
        } catch (java.util.zip.ZipException | java.io.EOFException damaged) {
            throw new CorruptException("objeto frio danificado: " + damaged.getMessage());
        }
    }

    /** Leitor de um objeto descomprimido; confere o resumo ao abrir. Seguro para várias threads. */
    public static final class Reader {
        private final MappedByteBuffer data;
        private final List<byte[]> sampledKeys = new ArrayList<>();
        private final List<Integer> sampledOffsets = new ArrayList<>();
        private final int end;
        private final long rows;

        private Reader(MappedByteBuffer data) throws CorruptException {
            this.data = data;
            int size = data.limit();
            if (size < MAGIC.length + TRAILER_BYTES) {
                throw new CorruptException("objeto frio curto demais");
            }
            end = size - TRAILER_BYTES;
            verify(size);
            int offset = MAGIC.length;
            long count = 0;
            while (offset < end) {
                int keyLength = length(offset);
                int valueAt = offset + 4 + keyLength;
                int valueLength = length(valueAt);
                if (count % SAMPLE == 0) {
                    sampledKeys.add(slice(offset + 4, keyLength));
                    sampledOffsets.add(offset);
                }
                offset = valueAt + 4 + valueLength;
                count++;
            }
            rows = count;
            if (offset != end || data.getInt(end) != -1 || data.getLong(end + 4) != count) {
                throw new CorruptException("o rodapé do objeto frio não confere");
            }
        }

        private void verify(int size) throws CorruptException {
            for (int i = 0; i < MAGIC.length; i++) {
                if (data.get(i) != MAGIC[i]) {
                    throw new CorruptException("não é um objeto frio");
                }
            }
            MessageDigest digest = sha256();
            ByteBuffer body = data.duplicate();
            body.position(0).limit(size - DIGEST_BYTES);
            digest.update(body);
            if (!Arrays.equals(digest.digest(), slice(size - DIGEST_BYTES, DIGEST_BYTES))) {
                throw new CorruptException("o resumo SHA-256 do objeto frio não confere");
            }
        }

        private int length(int offset) throws CorruptException {
            if (offset < 0 || offset > end - 4) {
                throw new CorruptException("entrada cortada no objeto frio");
            }
            int length = data.getInt(offset);
            if (length < 0 || length > end - offset - 4) {
                throw new CorruptException("tamanho inválido no objeto frio");
            }
            return length;
        }

        private byte[] slice(int offset, int length) {
            byte[] out = new byte[length];
            data.get(offset, out);
            return out;
        }

        public long rows() {
            return rows;
        }

        /** O valor da chave, se o objeto a tiver. */
        public Optional<byte[]> get(String key) {
            byte[] wanted = key.getBytes(StandardCharsets.UTF_8);
            int offset = seek(wanted);
            while (offset < end) {
                int keyLength = data.getInt(offset);
                int order = Arrays.compareUnsigned(slice(offset + 4, keyLength), wanted);
                int valueAt = offset + 4 + keyLength;
                int valueLength = data.getInt(valueAt);
                if (order == 0) {
                    return Optional.of(slice(valueAt + 4, valueLength));
                }
                if (order > 0) {
                    break;
                }
                offset = valueAt + 4 + valueLength;
            }
            return Optional.empty();
        }

        /**
         * Visita em ordem as entradas cuja chave começa com {@code prefix}.
         *
         * @return {@code false} quando o visitante pediu para parar
         */
        public boolean scan(String prefix, BiPredicate<String, byte[]> visitor) {
            byte[] wanted = prefix.getBytes(StandardCharsets.UTF_8);
            int offset = seek(wanted);
            while (offset < end) {
                int keyLength = data.getInt(offset);
                byte[] key = slice(offset + 4, keyLength);
                int valueAt = offset + 4 + keyLength;
                int valueLength = data.getInt(valueAt);
                if (key.length >= wanted.length && Arrays.equals(key, 0, wanted.length, wanted, 0, wanted.length)) {
                    if (!visitor.test(new String(key, StandardCharsets.UTF_8), slice(valueAt + 4, valueLength))) {
                        return false;
                    }
                } else if (Arrays.compareUnsigned(key, wanted) > 0) {
                    break;
                }
                offset = valueAt + 4 + valueLength;
            }
            return true;
        }

        /** A posição de onde procurar a chave: a última amostra menor ou igual a ela. */
        private int seek(byte[] wanted) {
            int low = 0;
            int high = sampledKeys.size() - 1;
            int found = MAGIC.length;
            while (low <= high) {
                int middle = (low + high) >>> 1;
                if (Arrays.compareUnsigned(sampledKeys.get(middle), wanted) <= 0) {
                    found = sampledOffsets.get(middle);
                    low = middle + 1;
                } else {
                    high = middle - 1;
                }
            }
            return found;
        }
    }

    public static Reader open(Path inflated) throws IOException {
        try (FileChannel channel = FileChannel.open(inflated, StandardOpenOption.READ)) {
            if (channel.size() > Integer.MAX_VALUE) {
                throw new CorruptException("objeto frio maior que 2 GB");
            }
            return new Reader(channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size()));
        }
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
