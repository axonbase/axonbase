package com.axonbase.core.partition.cold;

import com.axonbase.core.backup.RemoteBackupStore;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Camada fria num bucket compatível com S3 (MinIO do Enterprise ou AWS), pelo mesmo cliente do backup. Todas as chaves
 * ficam sob o prefixo configurado.
 */
public final class S3ColdStore implements ColdStore {

    private final RemoteBackupStore client;
    private final String bucket;
    private final String prefix;

    public S3ColdStore(RemoteBackupStore client, String bucket, String prefix) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("a camada fria precisa de um bucket");
        }
        this.client = client;
        this.bucket = bucket;
        String trimmed = prefix == null ? "" : prefix.replaceAll("^/+|/+$", "");
        this.prefix = trimmed.isEmpty() ? "" : trimmed + "/";
    }

    @Override
    public void upload(String key, Path file) throws IOException {
        client.uploadFile(bucket, prefix + key, file);
    }

    @Override
    public boolean download(String key, Path target) throws IOException {
        return client.downloadFile(bucket, prefix + key, target);
    }

    @Override
    public void write(String key, byte[] content) throws IOException {
        try (InputStream in = new ByteArrayInputStream(content)) {
            client.upload(bucket, prefix + key, in);
        }
    }

    @Override
    public Optional<byte[]> read(String key) throws IOException {
        Path temp = Files.createTempFile("axon-cold-", ".obj");
        try {
            return client.downloadFile(bucket, prefix + key, temp)
                ? Optional.of(Files.readAllBytes(temp)) : Optional.empty();
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public void delete(String key) {
        client.deleteObject(bucket, prefix + key);
    }

    @Override
    public List<String> list(String keyPrefix) {
        return client.listObjects(bucket, prefix + keyPrefix).stream()
            .filter(k -> k.startsWith(prefix))
            .map(k -> k.substring(prefix.length()))
            .sorted()
            .toList();
    }

    @Override
    public String describe() {
        return "s3://" + bucket + "/" + prefix;
    }
}
