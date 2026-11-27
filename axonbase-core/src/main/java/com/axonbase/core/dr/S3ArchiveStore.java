package com.axonbase.core.dr;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.core.backup.S3BackupClient;

import java.io.IOException;
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
        try {
            client.uploadFile(bucket, key, file);
        } catch (IOException e) {
            throw AxonError.internal(Messages.get("backup_s3_upload_failed", e.getMessage()));
        }
    }

    /** Um objeto inexistente é erro: quem restaura não pode seguir sem um segmento. */
    @Override
    public void get(String key, Path target) {
        try {
            if (!client.downloadFile(bucket, key, target)) {
                throw AxonError.internal(Messages.get("backup_s3_operation_failed", "download", 404, key));
            }
        } catch (IOException e) {
            throw AxonError.internal(Messages.get("backup_s3_download_failed", e.getMessage()));
        }
    }

    @Override
    public List<String> list(String prefix) {
        return client.listObjects(bucket, prefix).stream().sorted().toList();
    }
}
