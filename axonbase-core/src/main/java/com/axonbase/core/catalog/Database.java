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

    public Database(String ns, String db, KvBackend backend) {
        this.ns = ns;
        this.db = db;
        this.catalog = new Catalog();
        this.records = backend != null ? backend : new MemoryBackend();
        this.auditCatalog = new AiAuditCatalog(backend != null ? backend : new MemoryBackend());
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
}