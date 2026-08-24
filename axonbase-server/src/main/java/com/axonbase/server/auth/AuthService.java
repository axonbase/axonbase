package com.axonbase.server.auth;

import java.util.Map;
import com.axonbase.core.security.AuthCatalog;

/**
 * Servicio de autenticación: valida credenciais contra {@link UserStore} e
 * verifica JWT (JSON Web Token). O {@code signin} devolve un token asinado con
 * o segredo do servidor; {@code authenticate} valida un token xa existente.
 */
public final class AuthService {

    private final String secret;
    private final UserStore users;

    public AuthService(String secret, UserStore users) {
        this.secret = secret;
        this.users = users;
    }

    /** Valida usuário/senha e devolve un JWT, ou {@code null} se inválido. */
    public String signin(String user, String pass) {
        return signin(user, pass, null, null, null);
    }

    /**
     * Autentica no escopo solicitado e inclui o escopo efetivo no JWT. Um
     * access method, quando informado, também precisa existir no mesmo escopo.
     */
    public String signin(String user, String pass, String namespace, String database, String access) {
        AuthCatalog.User identity = users.verifyScoped(user, pass, namespace, database);
        if (identity == null) {
            return null;
        }
        if (access != null && !access.isBlank() && users.catalog().access(access, namespace, database) == null) {
            return null;
        }
        Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("id", identity.name());
        claims.put("scope", identity.scope().name());
        if (identity.namespace() != null) {
            claims.put("ns", identity.namespace());
        }
        if (identity.database() != null) {
            claims.put("db", identity.database());
        }
        if (!identity.roles().isEmpty()) {
            claims.put("roles", String.join(",", identity.roles()));
        }
        if (access != null && !access.isBlank()) {
            claims.put("access", access);
        }
        return Jwt.sign(secret, claims, 1_800_000L).value();
    }

    /** Verifica que un JWT sexa válido. Devolve {@code true} se válido. */
    public boolean authenticate(String token) {
        return claims(token) != null;
    }

    /** Claims verificados, ou {@code null} quando o token é inválido/expirado. */
    public Map<String, Object> claims(String token) {
        Map<String, Object> claims = token == null ? null : Jwt.verify(secret, token);
        if (claims == null || !(claims.get("exp") instanceof Number exp)
            || exp.longValue() < System.currentTimeMillis() / 1000) {
            return null;
        }
        return claims;
    }

    /** Verifica se os claims podem usar o namespace/banco pedidos. */
    public boolean allows(Map<String, Object> claims, String namespace, String database) {
        if (claims == null) {
            return false;
        }
        String scope = String.valueOf(claims.getOrDefault("scope", "ROOT"));
        if ("ROOT".equals(scope)) {
            return true;
        }
        String claimNs = String.valueOf(claims.getOrDefault("ns", ""));
        if (!claimNs.equals(namespace == null ? "" : namespace)) {
            return false;
        }
        if ("NAMESPACE".equals(scope)) {
            return true;
        }
        return "DATABASE".equals(scope)
            && String.valueOf(claims.getOrDefault("db", "")).equals(database == null ? "" : database);
    }

    /** Crea o usuario root se non existe (para o arranque do servidor). */
    public void ensureRoot(String user, String pass) {
        if (user != null && !user.isBlank()) {
            users.register(user, pass);
        }
    }
}
