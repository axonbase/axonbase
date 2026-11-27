package com.axonbase.core.shard;

import com.axonbase.value.AxonValue;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * O estado de um cliente no gateway durante uma requisição (ou uma conexão): banco escolhido, variáveis, saga aberta e
 * a credencial repassada aos shards. Não é compartilhada entre threads.
 */
public final class GatewaySession {

    /** A saga em que as próximas escritas entram. */
    record Saga(String name, String correlationId) {
    }

    private String namespace;
    private String database;
    private final String authorization;
    private final Map<String, AxonValue> vars = new LinkedHashMap<>();
    private Saga saga;
    /** A credencial já foi conferida num shard, nesta requisição e neste banco. */
    private boolean authenticated;
    /** Quanto do orçamento de cálculo local a requisição já usou. */
    private long spent;
    /** Shards em que a saga corrente já foi aberta por esta sessão. */
    private final Set<String> sagaShards = new LinkedHashSet<>();

    public GatewaySession(String namespace, String database) {
        this(namespace, database, null);
    }

    public GatewaySession(String namespace, String database, String authorization) {
        this.namespace = namespace;
        this.database = database;
        this.authorization = authorization;
    }

    public String namespace() {
        return namespace;
    }

    public String database() {
        return database;
    }

    public String authorization() {
        return authorization;
    }

    public Map<String, AxonValue> vars() {
        return vars;
    }

    boolean authenticated() {
        return authenticated;
    }

    void authenticated(boolean value) {
        authenticated = value;
    }

    long spent() {
        return spent;
    }

    void spend(long size) {
        spent += size;
    }

    /** Uma requisição nova confere a credencial de novo e tem o orçamento inteiro. */
    void newRequest() {
        authenticated = false;
        spent = 0;
    }

    void use(String nextNamespace, String nextDatabase) {
        // a credencial vale por banco
        authenticated = false;
        if (nextNamespace != null) {
            namespace = nextNamespace;
        }
        if (nextDatabase != null) {
            database = nextDatabase;
        }
    }

    Saga saga() {
        return saga;
    }

    void saga(Saga next) {
        saga = next;
        sagaShards.clear();
    }

    Set<String> sagaShards() {
        return sagaShards;
    }
}
