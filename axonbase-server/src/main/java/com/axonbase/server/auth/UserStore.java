package com.axonbase.server.auth;

import com.axonbase.core.security.AuthCatalog;

import java.util.List;

/**
 * Adaptador do catálogo de identidades do motor para o servidor HTTP. A API
 * curta original continua disponível para o usuário root, e as sobrecargas com
 * namespace/banco permitem autenticação limitada por escopo.
 */
public final class UserStore {

    private final AuthCatalog catalog;

    public UserStore() {
        this(new AuthCatalog());
    }

    public UserStore(AuthCatalog catalog) {
        this.catalog = catalog;
    }

    /** Alta de usuário root, preservada para o bootstrap do servidor. */
    public boolean register(String user, String pass) {
        boolean existed = catalog.users().stream().anyMatch(u -> u.name().equals(user)
            && u.scope() == AuthCatalog.Scope.ROOT);
        catalog.ensureUser(user, pass);
        return !existed;
    }

    public AuthCatalog.User verifyScoped(String user, String pass, String namespace, String database) {
        return catalog.verify(user, pass, namespace, database);
    }

    /** Verifica credenciais root, para compatibilidade. */
    public boolean verify(String user, String pass) {
        return verifyScoped(user, pass, null, null) != null;
    }

    public boolean contains(String user) {
        return catalog.users().stream().anyMatch(u -> u.name().equals(user));
    }

    public AuthCatalog catalog() {
        return catalog;
    }

    public void define(String name, AuthCatalog.Scope scope, String namespace, String database,
                       String password, List<String> roles) {
        catalog.defineUser(name, scope, namespace, database, password, roles);
    }
}
