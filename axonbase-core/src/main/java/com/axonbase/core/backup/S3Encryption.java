package com.axonbase.core.backup;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Arrays;
import java.util.Base64;

/**
 * Criptografia AES-256-GCM dos backups S3.
 *
 * <p>A senha vira uma chave de 256 bits via PBKDF2-HMAC-SHA-256 com 10000 iterações e
 * um salt fixo, porque o formato no bucket é só {@code nonce + ciphertext + tag}. Cada
 * upload gera um nonce de 12 bytes novo com {@link SecureRandom}. O header de texto
 * {@code --axonbase-backup-v1--} vira {@code --axonbase-backup-encrypted-v1--}, seguido
 * de uma única linha Base64 com o payload. O download detecta o header e decripta.</p>
 */
public final class S3Encryption {

    public static final String PLAIN_HEADER = "--axonbase-backup-v1--";
    public static final String ENCRYPTED_HEADER = "--axonbase-backup-encrypted-v1--";

    private static final int ITERATIONS = 10_000;
    private static final int KEY_BITS = 256;
    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final byte[] SALT = "axonbase-backup-salt-v1".getBytes(StandardCharsets.UTF_8);

    private S3Encryption() {
    }

    /**
     * Encripta o dump textual. Devolve o texto do backup com o header trocado para
     * {@link #ENCRYPTED_HEADER} e o payload {@code base64(nonce || ciphertext || tag)}.
     */
    public static String encrypt(String plainDump, String password) {
        byte[] nonce = new byte[NONCE_BYTES];
        new SecureRandom().nextBytes(nonce);
        try {
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, deriveKey(password), nonce);
            byte[] ciphertext = cipher.doFinal(plainDump.getBytes(StandardCharsets.UTF_8));
            byte[] payload = new byte[nonce.length + ciphertext.length];
            System.arraycopy(nonce, 0, payload, 0, nonce.length);
            System.arraycopy(ciphertext, 0, payload, nonce.length, ciphertext.length);
            return ENCRYPTED_HEADER + "\n"
                + Base64.getEncoder().encodeToString(payload) + "\n";
        } catch (Exception e) {
            throw AxonError.internal(Messages.get("backup_encrypt_failed", e.getMessage()));
        }
    }

    /** Encripta bytes diretamente, com o mesmo formato do {@link #encrypt(String, String)}. */
    public static byte[] encryptBytes(byte[] plainDump, String password) {
        return encrypt(new String(plainDump, StandardCharsets.UTF_8), password)
            .getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Decripta um texto de backup. Se o header for o plano, devolve inalterado. Se for o
     * encriptado, exige a senha e valida o tag GCM. Senha errada ou payload corrompido
     * vira {@link AxonError} e nunca devolve bytes parciais.
     */
    public static String decrypt(String dump, String password) {
        return new String(decryptBytes(dump.getBytes(StandardCharsets.UTF_8), password),
            StandardCharsets.UTF_8);
    }

    /** Decripta um array de bytes de backup, com a mesma troca de header do {@link #decrypt}. */
    public static byte[] decryptBytes(byte[] dump, String password) {
        String text = new String(dump, StandardCharsets.UTF_8);
        if (!text.startsWith(ENCRYPTED_HEADER)) {
            return dump;
        }
        if (password == null || password.isBlank()) {
            throw AxonError.internal(Messages.get("backup_encryption_key_required"));
        }
        try {
            int headerEnd = text.indexOf('\n');
            String payloadLine = headerEnd >= 0 ? text.substring(headerEnd + 1).trim() : "";
            if (payloadLine.isEmpty()) {
                throw AxonError.internal(Messages.get("backup_encrypted_payload_missing"));
            }
            byte[] payload = Base64.getDecoder().decode(payloadLine);
            if (payload.length <= NONCE_BYTES) {
                throw AxonError.internal(Messages.get("backup_encrypted_nonce_missing"));
            }
            byte[] nonce = Arrays.copyOfRange(payload, 0, NONCE_BYTES);
            byte[] ciphertext = Arrays.copyOfRange(payload, NONCE_BYTES, payload.length);
            Cipher cipher = cipher(Cipher.DECRYPT_MODE, deriveKey(password), nonce);
            return cipher.doFinal(ciphertext);
        } catch (AxonError e) {
            throw e;
        } catch (Exception e) {
            throw AxonError.internal(Messages.get("backup_decrypt_failed", e.getMessage()));
        }
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
        return cipher;
    }

    private static byte[] deriveKey(String password) throws Exception {
        char[] chars = (password == null ? "" : password).toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, SALT, ITERATIONS, KEY_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return factory.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }

    /** SHA-256 simples, usado para detectar a integridade do header antes de decriptar. */
    static String sha256(byte[] data) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new IllegalStateException(Messages.get("crypto_sha256_unavailable"), e);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
