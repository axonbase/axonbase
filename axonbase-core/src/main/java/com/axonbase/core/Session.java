package com.axonbase.core;

import com.axonbase.core.storage.Transaction;
import com.axonbase.value.AxonValue;

/**
 * Sesión de execución: leva o namespace e banco de datos actuais, o auth e os
 * variables. Cada request do wire crea ou reutiliz a sesión adecuado.
 */
public final class Session {

    private String namespace;
    private String database;
    private final Variables vars = new Variables();
    private AxonValue auth;

    public Session(String namespace, String database) {
        this.namespace = namespace;
        this.database = database;
    }

    public static Session create() {
        return new Session(null, null);
    }

    public String namespace() {
        return namespace;
    }

    public void namespace(String ns) {
        this.namespace = ns;
    }

    public String database() {
        return database;
    }

    public void database(String db) {
        this.database = db;
    }

    public Variables vars() {
        return vars;
    }

    public AxonValue auth() {
        return auth;
    }

    public void auth(AxonValue auth) {
        this.auth = auth;
    }

    // ------------------------------------------------------------------
    // transação activa
    // ------------------------------------------------------------------

    private Transaction txt;

    public boolean inTransaction() {
        return txt != null && txt.isOpen();
    }

    public Transaction tx() {
        return txt;
    }

    public void tx(Transaction tx) {
        this.txt = tx;
    }

    // ------------------------------------------------------------------
    // Live queries (tempo real)
    // ------------------------------------------------------------------

    private volatile com.axonbase.core.engine.LiveBus.Listener liveListener;
    private final java.util.Set<String> liveIds =
        java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.List<Runnable> pendingLive =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** Listener que recebe as notificações das live queries desta sessão. */
    public com.axonbase.core.engine.LiveBus.Listener liveListener() {
        return liveListener;
    }

    public void liveListener(com.axonbase.core.engine.LiveBus.Listener listener) {
        this.liveListener = listener;
    }

    /** Ids das live queries criadas por esta sessão. */
    public java.util.Set<String> liveIds() {
        return liveIds;
    }

    public void trackLive(String id) {
        liveIds.add(id);
    }

    public void untrackLive(String id) {
        liveIds.remove(id);
    }

    public void clearLive() {
        liveIds.clear();
    }

    /**
     * Enfileira uma entrega de notificação enquanto existe transação aberta: só
     * sai para o cliente no COMMIT, para não expor escritas não confirmadas.
     */
    public void queueLive(Runnable delivery) {
        pendingLive.add(delivery);
    }

    /** Entrega as notificações acumuladas (chamado no COMMIT). */
    public void flushLive() {
        java.util.List<Runnable> copy;
        synchronized (pendingLive) {
            copy = new java.util.ArrayList<>(pendingLive);
            pendingLive.clear();
        }
        copy.forEach(Runnable::run);
    }

    /** Descarta as notificações acumuladas (chamado no CANCEL). */
    public void discardLive() {
        pendingLive.clear();
    }
}