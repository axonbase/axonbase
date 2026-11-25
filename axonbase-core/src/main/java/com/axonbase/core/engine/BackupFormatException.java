package com.axonbase.core.engine;

/** Erro de backup com código estável ({@code BACKUP_*}) e status HTTP sugerido (400 ou 409). */
public final class BackupFormatException extends RuntimeException {

    public static final String CHAIN_INVALID = "BACKUP_CHAIN_INVALID";
    public static final String CHECKSUM = "BACKUP_CHECKSUM";
    public static final String TRUNCATED = "BACKUP_TRUNCATED";
    public static final String INVALID = "BACKUP_INVALID";
    public static final String SINCE_AHEAD = "BACKUP_SINCE_AHEAD";

    private final String code;
    private final int status;

    public BackupFormatException(String code, int status, String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    public String code() {
        return code;
    }

    public int status() {
        return status;
    }
}
