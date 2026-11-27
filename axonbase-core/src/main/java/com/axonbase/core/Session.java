package com.axonbase.core;

import com.axonbase.core.storage.Transaction;
import com.axonbase.common.Messages;
import com.axonbase.core.security.Authz;
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
    private Authz.Role roleCap;
    private boolean capReadOnly;
    private boolean authReadOnly;
    // Saga active state
    private String sagaName;
    private String sagaCorrelationId;

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

    /** {@code $auth} e {@code $token}: numa sessão autenticada o servidor os preenche com os claims. */
    public static boolean isIdentityVariable(String name) {
        return "auth".equals(name) || "token".equals(name);
    }

    /** Recusa a definição, pelo cliente, de uma variável de identidade numa sessão autenticada. */
    public void requireDefinable(String name) {
        if (isIdentityVariable(name) && Authz.authenticated(auth)) {
            throw new com.axonbase.common.AxonError(-32002, Messages.get("auth_param_reserved", name));
        }
    }

    /**
     * Valor de um parâmetro {@code $nome}, ou {@code null} quando não há. As variáveis de identidade vêm dos claims
     * sempre que a sessão está autenticada, mesmo que exista uma variável com o mesmo nome definida antes.
     */
    public AxonValue parameter(String name) {
        if (isIdentityVariable(name) && Authz.authenticated(auth)) {
            return auth;
        }
        return vars.get(name);
    }

    /**
     * Define os claims da sessão. Aplica o teto de papel (se houver) e recalcula a
     * marca de somente leitura: papel explícito viewer vira somente leitura.
     */
    public void auth(AxonValue auth) {
        AxonValue effective = roleCap == null ? auth : Authz.cap(auth, roleCap);
        this.auth = effective;
        this.authReadOnly = Authz.role(effective) == Authz.Role.VIEWER;
    }

    /** Teto de papel pedido pelo cliente (cabeçalho Axon-Role-Cap): só rebaixa, nunca eleva. */
    public void roleCap(Authz.Role cap) {
        this.roleCap = cap;
        if (cap == Authz.Role.VIEWER) {
            this.capReadOnly = true;
        }
        if (auth != null) {
            auth(auth);
        }
    }

    /** True quando o motor deve recusar qualquer escrita nesta sessão. */
    public boolean readOnly() {
        return capReadOnly || authReadOnly;
    }

    private boolean restoring;

    /**
     * Sessão que repõe dados que já existiam (importação de dump, movimentação de shard, compensação de saga): um
     * CREATE ou INSERT sobre um id existente o substitui, e as restrições de tabela não são conferidas. Só o motor
     * liga isto; nenhum pedido de cliente chega aqui.
     */
    public boolean restoring() {
        return restoring;
    }

    public void restoring(boolean value) {
        this.restoring = value;
    }

    /**
     * Sessão nova para repor dados em nome desta: mesmo namespace e banco, mesma identidade e mesmo teto de papel, sem
     * a transação, as variáveis nem as live queries dela.
     */
    public Session restoringCopy() {
        Session copy = new Session(namespace, database);
        copy.auth = auth;
        copy.roleCap = roleCap;
        copy.capReadOnly = capReadOnly;
        copy.authReadOnly = authReadOnly;
        copy.restoring = true;
        return copy;
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

    private long txControlVersion = -1;

    /** Versão do plano de controle quando a transação começou, ou {@code -1} se não se sabe. */
    public long txControlVersion() {
        return txControlVersion;
    }

    public void txControlVersion(long version) {
        this.txControlVersion = version;
    }

    private long txIndexEpoch = -1;

    /** Época de índices do banco quando a transação começou, ou {@code -1} se não se sabe. */
    public long txIndexEpoch() {
        return txIndexEpoch;
    }

    public void txIndexEpoch(long epoch) {
        this.txIndexEpoch = epoch;
    }

    private Transaction requireTx() {
        if (txt == null || !txt.isOpen()) {
            throw new com.axonbase.common.AxonError(-32000, Messages.get("txn_inactive"));
        }
        return txt;
    }

    /** Delega na transacción activa: crea un savepoint co nome dado. */
    public void savepoint(String name) {
        requireTx().savepoint(name);
    }

    /** Delega na transacción activa: elimina o savepoint nomeado. */
    public void release(String name) {
        requireTx().release(name);
    }

    /** Delega na transacción activa: retrocede ao savepoint nomeado. */
    public void rollbackTo(String name) {
        requireTx().rollbackTo(name);
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

    // ------------------------------------------------------------------
    // Ações presas ao destino das escritas da transação
    // ------------------------------------------------------------------

    private final java.util.List<Runnable> discardActions = new java.util.ArrayList<>();

    /** Ação a executar se as escritas da transação em curso forem descartadas. */
    public synchronized void onWritesDiscarded(Runnable action) {
        discardActions.add(action);
    }

    /** A transação foi desfeita sem gravar: executa as ações registradas. */
    public void writesDiscarded() {
        java.util.List<Runnable> copy;
        synchronized (this) {
            copy = new java.util.ArrayList<>(discardActions);
            discardActions.clear();
        }
        copy.forEach(Runnable::run);
    }

    /** As escritas foram gravadas, ou não se sabe: as ações são esquecidas. */
    public synchronized void keepWrites() {
        discardActions.clear();
    }

    // ------------------------------------------------------------------
    // Saga (transação distribuída)
    // ------------------------------------------------------------------

    public boolean inSaga() {
        return sagaName != null && sagaCorrelationId != null;
    }

    public String sagaName() {
        return sagaName;
    }

    public String sagaCorrelationId() {
        return sagaCorrelationId;
    }

    public void sagaBegin(String name, String correlationId) {
        this.sagaName = name;
        this.sagaCorrelationId = correlationId;
    }

    public void sagaEnd() {
        this.sagaName = null;
        this.sagaCorrelationId = null;
    }
}
