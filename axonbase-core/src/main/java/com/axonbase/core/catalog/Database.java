package com.axonbase.core.catalog;

import com.axonbase.core.audit.AiAuditCatalog;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;

/**
 * Unha banco de datos nomeado dentro dun namespace. Leva o seu propio
 * {@link Catalog} e unha {@link KvBackend} para os records.
 */
public final class Database {

    private final String ns;
    private final String db;
    private final Catalog catalog;
    private final KvBackend records;
    private final AiAuditCatalog auditCatalog;
    private final com.axonbase.core.pl.ProgramCatalog programs = new com.axonbase.core.pl.ProgramCatalog();

    /**
     * Escritas seguram a parte de leitura durante o statement; a construção de índice segura a de escrita
     * por um bloco curto de linhas, para que o bloco não veja uma escrita pela metade.
     */
    private final java.util.concurrent.locks.ReentrantReadWriteLock indexLock =
        new java.util.concurrent.locks.ReentrantReadWriteLock();
    private volatile int indexInlineRows = defaultInlineRows();

    private static int defaultInlineRows() {
        String env = System.getenv("AXON_INDEX_INLINE_ROWS");
        try {
            return env == null || env.isBlank() ? 10_000 : Integer.parseInt(env.trim());
        } catch (NumberFormatException e) {
            return 10_000;
        }
    }

    public Database(String ns, String db, KvBackend backend) {
        this.ns = ns;
        this.db = db;
        this.catalog = new Catalog();
        this.records = backend != null ? backend : new MemoryBackend();
        this.auditCatalog = new AiAuditCatalog(backend != null ? backend : new MemoryBackend());
    }

    public java.util.concurrent.locks.ReentrantReadWriteLock indexLock() {
        return indexLock;
    }

    /** Tabelas com até este número de linhas criam índice no próprio comando; maiores vão para segundo plano. */
    public int indexInlineRows() {
        return indexInlineRows;
    }

    public void indexInlineRows(int rows) {
        this.indexInlineRows = Math.max(0, rows);
    }

    public String ns() {
        return ns;
    }

    public String db() {
        return db;
    }

    public Catalog catalog() {
        return catalog;
    }

    public KvBackend records() {
        return records;
    }

    public AiAuditCatalog auditCatalog() {
        return auditCatalog;
    }

    /** Funções, procedures e packages AxonPL deste banco. */
    public com.axonbase.core.pl.ProgramCatalog programs() {
        return programs;
    }
}
