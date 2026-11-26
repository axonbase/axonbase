package com.axonbase.core.backup;

import java.io.InputStream;
import java.util.List;

/**
 * Interface plugável para o armazenamento remoto de backups.
 *
 * <p>A implementação concreta ({@link S3BackupClient}) fala HTTP com MinIO/S3;
 * testes podem usar uma implementação em memória sem rede.</p>
 */
public interface RemoteBackupStore {

    /** Faz upload de um stream de dados, usando chunked streaming. */
    void upload(String bucket, String key, InputStream data);

    /** Faz download de um objeto. Devolve array vazio quando não existe. */
    byte[] download(String bucket, String key);

    /** Lista objetos cuja chave comece com {@code prefix}. */
    List<String> listObjects(String bucket, String prefix);

    /** Apaga um objeto. */
    void deleteObject(String bucket, String key);

    /** Envia um arquivo. Implementações reais sobrescrevem para não o carregar inteiro na memória. */
    default void uploadFile(String bucket, String key, java.nio.file.Path file) throws java.io.IOException {
        try (InputStream in = java.nio.file.Files.newInputStream(file)) {
            upload(bucket, key, in);
        }
    }

    /**
     * Baixa um objeto para o arquivo.
     *
     * @return {@code false} quando o objeto não existe
     */
    default boolean downloadFile(String bucket, String key, java.nio.file.Path target) throws java.io.IOException {
        byte[] data = download(bucket, key);
        if (data.length == 0) {
            return false;
        }
        java.nio.file.Files.write(target, data);
        return true;
    }
}