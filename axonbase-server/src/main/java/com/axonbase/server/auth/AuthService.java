package com.axonbase.server.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.common.Messages;

/**
 * Servicio de autenticación: valida credenciais contra {@link UserStore} e
 * verifica JWT (JSON Web Token). O {@code signin} devolve un token asinado con
 * o segredo do servidor; {@code authenticate} valida un token xa existente.
 */
public final class AuthService {

    private final String secret;
    private final UserStore users;
    private final Map<String, TemporaryCredential> temporaryCredentials = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /** Cache JWT por credencial temporária para reuso na mesma sessão. */
    private final Map<String, String> credentialJwts = new ConcurrentHashMap<>();

    private record TemporaryCredential(String user, String certificate, String fingerprint, long expiresAt) {
        boolean matches(String requestedUser) {
            return user.equals(requestedUser) && System.currentTimeMillis() <= expiresAt;
        }

        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

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
     *
     * <p>Credenciais temporárias ({@code axontc_*}) não são consumidas na
     * primeira autenticação: ficam no mapa até expirar, e o JWT emitido é
     * cacheado para reuso. Isso permite que o DataGrip (ou outro cliente)
     * reconecte múltiplas vezes com a mesma senha temporária sem perder a
     * sessão, mas impede uso simultâneo em outro cliente porque a credencial
     * expira em 60 segundos.
     */
    public String signin(String user, String pass, String namespace, String database, String access) {
        if (pass != null && pass.startsWith("axontc_")) {
            String cached = credentialJwts.get(pass);
            if (cached != null) {
                Map<String, Object> claims = claims(cached);
                if (claims != null) {
                    return cached;
                }
                credentialJwts.remove(pass);
            }
        }

        AuthCatalog.User identity = consumeTemporaryCredential(user, pass, namespace, database);
        if (identity == null) {
            identity = users.verifyScoped(user, pass, namespace, database);
        }
        if (identity == null) {
            return null;
        }
        if (access != null && !access.isBlank() && users.catalog().access(access, namespace, database) == null) {
            return null;
        }
        Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("id", identity.name());
        claims.put("jti", UUID.randomUUID().toString());
        claims.put("typ", "access");
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
        if (!identity.dataRules().isEmpty()) {
            claims.put("data_rules", String.join(",", identity.dataRules()));
        }
        if (access != null && !access.isBlank()) {
            claims.put("access", access);
        }
        String jwt = Jwt.sign(secret, claims, 1_800_000L).value();

        if (pass != null && pass.startsWith("axontc_")) {
            credentialJwts.put(pass, jwt);
        }

        return jwt;
    }

    /** Emite uma senha opaca de uso único, sem amarrar a um namespace/banco. */
    public String issueTemporaryCredential(String user, String certificateStore, String fingerprint) {
        cleanup();
        AuthCatalog.User identity = users.catalog().certificateUser(user, certificateStore, fingerprint);
        if (identity == null) {
            System.err.println("{\"event\":\"cert_issue_failed\",\"user\":\""
                + (user == null ? "" : user) + "\",\"store\":\"" + certificateStore
                + "\",\"total_users\":" + users.catalog().users().size()
                + ",\"user_names\":" + users.catalog().users().stream().map(u -> u.name()).toList() + "}");
            throw new IllegalArgumentException(Messages.get("cert_user_missing"));
        }
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String credential = "axontc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        temporaryCredentials.put(credential, new TemporaryCredential(user,
            identity.certificate(), identity.fingerprint(), System.currentTimeMillis() + 60_000L));
        return credential;
    }

    private void cleanup() {
        temporaryCredentials.entrySet().removeIf(entry -> entry.getValue().expired());
        credentialJwts.keySet().removeIf(key -> {
            TemporaryCredential tc = temporaryCredentials.get(key);
            return tc == null || tc.expired();
        });
    }

    private static boolean matchesFingerprint(AuthCatalog.User identity, String fingerprint) {
        // Sem FINGERPRINT definido, qualquer certificado confiável do JKS vale.
        if (identity.fingerprint() == null || identity.fingerprint().isBlank()) {
            return true;
        }
        return java.util.Objects.equals(identity.fingerprint(), fingerprint);
    }

    private AuthCatalog.User consumeTemporaryCredential(String user, String pass, String namespace,
                                                        String database) {
        if (pass == null || !pass.startsWith("axontc_")) {
            return null;
        }
        cleanup();
        TemporaryCredential credential = temporaryCredentials.get(pass);
        if (credential == null || credential.expired()) {
            temporaryCredentials.remove(pass);
            credentialJwts.remove(pass);
            return null;
        }
        if (!credential.matches(user)) {
            return null;
        }
        AuthCatalog.User identity = users.catalog().user(user, namespace, database);
        return identity != null && identity.certificateBased()
            && identity.certificate().equals(credential.certificate())
            && matchesFingerprint(identity, credential.fingerprint()) ? identity : null;
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

    /**
     * Altas de usuário por RPC (signup): cria uma identidade de escopo DATABASE
     * no namespace/banco indicados e devolve um JWT pronto para a sessão. É o
     * mínimo viável: sem access methods, sem dados de perfil, só a identidade.
     * Sem um namespace/banco o {@code null} devolvido sinaliza escopo ausente.
     */
    public String signup(String user, String pass, String namespace, String database) {
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException(Messages.get("auth_signup_user_invalid"));
        }
        if (namespace == null || namespace.isBlank() || database == null || database.isBlank()) {
            return null;
        }
        users.catalog().defineUser(user, AuthCatalog.Scope.DATABASE, namespace, database,
            pass == null ? "" : pass, java.util.List.of(), java.util.List.of());
        return signin(user, pass == null ? "" : pass, namespace, database, null);
    }
}
