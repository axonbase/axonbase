package com.axonbase.core.backup;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;

/**
 * Criptografia AES-256-GCM em streaming do formato {@code .axbak} (formato de blocos v2).
 *
 * <p>Layout: a linha de cabeçalho {@link #HEADER} e, em seguida, uma linha Base64 por bloco com
 * {@code nonce(12) || ciphertext || tag}. Cada bloco cifra até {@link #BLOCK_SIZE} bytes do dump
 * em texto claro (inclusive o cabeçalho plano {@code --axonbase-backup-v1--}) e usa um nonce
 * aleatório próprio. O AAD de cada bloco é o índice (8 bytes) mais um byte indicando se é o último,
 * o que impede reordenar, remover ou truncar blocos sem que o GCM acuse. A chave vem do mesmo
 * PBKDF2 do {@link S3Encryption}. Só um bloco fica em memória por vez.</p>
 */
public final class StreamEncryption {

    public static final String HEADER = "--axonbase-backup-encrypted-v2--";
    public static final int BLOCK_SIZE = 64 * 1024;

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private StreamEncryption() {
    }

    /** Escreve o cabeçalho v2 em {@code sink} e devolve um stream que cifra tudo o que receber. */
    public static OutputStream encrypting(OutputStream sink, String password) throws IOException {
        byte[] key = S3Encryption.deriveKeyBytes(password);
        sink.write((HEADER + "\n").getBytes(StandardCharsets.US_ASCII));
        return new EncryptingStream(sink, key);
    }

    /** Decifra o corpo (blocos, sem o cabeçalho v2, que já deve ter sido consumido). */
    public static InputStream decrypting(InputStream blocks, String password) {
        if (password == null || password.isBlank()) {
            throw AxonError.internal(Messages.get("backup_encryption_key_required"));
        }
        return new DecryptingStream(blocks, S3Encryption.deriveKeyBytes(password));
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, long index, boolean last) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(ByteBuffer.allocate(9).putLong(index).put((byte) (last ? 1 : 0)).array());
            return c;
        } catch (Exception e) {
            throw AxonError.internal(Messages.get("backup_encrypt_failed", e.getMessage()));
        }
    }

    private static final class EncryptingStream extends OutputStream {
        private final OutputStream sink;
        private final byte[] key;
        private final byte[] buf = new byte[BLOCK_SIZE];
        private final Base64.Encoder b64 = Base64.getEncoder();
        private int len;
        private long index;
        private boolean closed;

        EncryptingStream(OutputStream sink, byte[] key) {
            this.sink = sink;
            this.key = key;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] data, int off, int n) throws IOException {
            while (n > 0) {
                if (len == BLOCK_SIZE) {
                    // há mais dados: o bloco cheio não é o último
                    emit(false);
                }
                int take = Math.min(n, BLOCK_SIZE - len);
                System.arraycopy(data, off, buf, len, take);
                len += take;
                off += take;
                n -= take;
            }
        }

        private void emit(boolean last) throws IOException {
            byte[] nonce = new byte[NONCE_BYTES];
            RANDOM.nextBytes(nonce);
            try {
                byte[] ct = cipher(Cipher.ENCRYPT_MODE, key, nonce, index++, last).doFinal(buf, 0, len);
                byte[] block = new byte[NONCE_BYTES + ct.length];
                System.arraycopy(nonce, 0, block, 0, NONCE_BYTES);
                System.arraycopy(ct, 0, block, NONCE_BYTES, ct.length);
                sink.write(b64.encode(block));
                sink.write('\n');
            } catch (java.security.GeneralSecurityException e) {
                throw new IOException(e);
            }
            len = 0;
        }

        @Override
        public void flush() throws IOException {
            sink.flush();
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            emit(true);
            sink.flush();
            sink.close();
        }
    }

    private static final class DecryptingStream extends InputStream {
        private final BufferedReader lines;
        private final byte[] key;
        private final Base64.Decoder b64 = Base64.getDecoder();
        private String pending;
        private boolean started;
        private long index;
        private byte[] current = new byte[0];
        private int pos;
        private boolean done;

        DecryptingStream(InputStream src, byte[] key) {
            this.lines = new BufferedReader(new InputStreamReader(src, StandardCharsets.US_ASCII), 1 << 16);
            this.key = key;
        }

        private boolean fill() throws IOException {
            while (pos >= current.length) {
                if (done) {
                    return false;
                }
                if (!started) {
                    pending = lines.readLine();
                    started = true;
                }
                if (pending == null) {
                    // sem nenhum bloco: backup truncado
                    throw new IOException(Messages.get("backup_stream_truncated"));
                }
                String line = pending;
                pending = lines.readLine();
                boolean last = pending == null;
                current = open(line, last);
                pos = 0;
                done = last;
            }
            return true;
        }

        private byte[] open(String line, boolean last) throws IOException {
            try {
                byte[] block = b64.decode(line.trim());
                if (block.length <= NONCE_BYTES) {
                    throw new IOException(Messages.get("backup_encrypted_nonce_missing"));
                }
                byte[] nonce = Arrays.copyOfRange(block, 0, NONCE_BYTES);
                return cipher(Cipher.DECRYPT_MODE, key, nonce, index++, last)
                    .doFinal(block, NONCE_BYTES, block.length - NONCE_BYTES);
            } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
                // senha errada, bloco adulterado, reordenado ou backup truncado
                throw new IOException(Messages.get("backup_decrypt_failed", e.getMessage()), e);
            }
        }

        @Override
        public int read() throws IOException {
            return fill() ? current[pos++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] b, int off, int n) throws IOException {
            if (n == 0) {
                return 0;
            }
            if (!fill()) {
                return -1;
            }
            int take = Math.min(n, current.length - pos);
            System.arraycopy(current, pos, b, off, take);
            pos += take;
            return take;
        }

        @Override
        public void close() throws IOException {
            lines.close();
        }
    }
}
