package com.axonbase.core.dr;

import com.axonbase.core.backup.S3BackupClient;

import java.nio.file.Path;
import java.util.List;

/** Arquivamento num balde S3 (Simple Storage Service) ou compatível, como o MinIO. */
public final class S3ArchiveStore implements ArchiveStore {

    private final S3BackupClient client;
    private final String bucket;

    public S3ArchiveStore(S3BackupClient client, String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void put(String key, Path file) {
        client.uploadFile(bucket, key, file);
    }

    @Override
    public void get(String key, Path target) {
        client.downloadFile(bucket, key, target);
    }

    @Override
    public List<String> list(String prefix) {
        return client.listObjects(bucket, prefix).stream().sorted().toList();
    }
}
