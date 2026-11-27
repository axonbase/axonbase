package com.axonbase.server.auth;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.axonbase.core.security.AuthCatalog;
import com.axonbase.common.Messages;

public final class AuthService {

    private static final long COOLDOWN_MS = 600_000L;

    private final String secret;
    private final UserStore users;
    private final Map<String, TemporaryCredential> temporaryCredentials = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final ServiceTokenStore serviceTokens = new ServiceTokenStore();
    /** jti revogado, com o exp do JWT para poder esquecer a entrada depois. */
    private final Map<String, Long> revokedJwts = new ConcurrentHashMap<>();
    private final Map<String, Long> lastCredentialIssuance = new ConcurrentHashMap<>();

    /** Primeiro login de cada credencial temporária, para reconectar com o mesmo usuário e escopo até ela expirar. */
    private final Map<String, CredentialLogin> credentialLogins = new ConcurrentHashMap<>();

    private record CredentialLogin(String jwt, String user, String namespace, String database, String access,
                                   long expiresAt) {
        boolean boundTo(String requestedUser, String ns, String db, String requestedAccess) {
            return java.util.Objects.equals(user, requestedUser) && java.util.Objects.equals(namespace, ns)
                && java.util.Objects.equals(database, db) && java.util.Objects.equals(access, requestedAccess);
        }

        boolean expired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }

    private record TemporaryCredential(String user, String certificate, String fingerprint,
                                       boolean identityFromCertificate, long expiresAt) {
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
     * primeira autenticação: até expirar, 60 segundos depois da emissão, o mesmo
     * usuário no mesmo namespace, banco e access method recebe o mesmo JWT, o que
     * permite ao DataGrip (ou outro cliente) reconectar. Qualquer outro usuário
     * ou escopo, ou um pedido depois do prazo, é recusado.
     */
    public String signin(String user, String pass, String namespace, String database, String access) {
        if (pass != null && pass.startsWith("axontc_")) {
            CredentialLogin first = credentialLogins.get(pass);
            if (first != null) {
                if (first.expired() || !first.boundTo(user, namespace, database, access)) {
                    return null;
                }
                if (claims(first.jwt()) != null) {
                    return first.jwt();
                }
                credentialLogins.remove(pass);
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
        trackSession(claims, 1_800_000L);

        TemporaryCredential used = pass == null ? null : temporaryCredentials.get(pass);
        if (used != null) {
            credentialLogins.put(pass,
                new CredentialLogin(jwt, user, namespace, database, access, used.expiresAt()));
        }

        return jwt;
    }

    /**
     * Emite uma credencial para um nome informado pelo cliente, quando o certificado não identifica ninguém: só vale
     * para usuário cujo FINGERPRINT é o do certificado apresentado.
     */
    public String issueTemporaryCredential(String user, String certificateStore, String fingerprint) {
        return issue(user, certificateStore, fingerprint, false);
    }

    /**
     * Emite uma credencial para o usuário que o próprio certificado identifica. O nome precisa ter sido extraído do
     * certificado pelo servidor, nunca recebido do cliente.
     */
    public String issueCredentialForCertificate(String certificateUser, String certificateStore, String fingerprint) {
        return issue(certificateUser, certificateStore, fingerprint, true);
    }

    /** Senha opaca e temporária, sem amarrar a um namespace/banco até o primeiro uso. */
    private String issue(String user, String certificateStore, String fingerprint, boolean fromCertificate) {
        cleanup();
        AuthCatalog.User identity = fromCertificate
            ? users.catalog().certificateUser(user, certificateStore, fingerprint)
            : pinnedUser(user, certificateStore, fingerprint);
        if (identity == null) {
            com.axonbase.server.AxonLog.log("warn", "cert_issue_failed", "credencial temporária recusada",
                Map.of("user", user == null ? "" : user, "store", certificateStore == null ? "" : certificateStore));
            throw new IllegalArgumentException(Messages.get(
                fromCertificate ? "cert_user_missing" : "cert_fingerprint_required"));
        }
        String userKey = user + "@" + certificateStore;
        Long lastIssue = lastCredentialIssuance.get(userKey);
        long now = System.currentTimeMillis();
        if (lastIssue != null && (now - lastIssue) < COOLDOWN_MS) {
            long remaining = (COOLDOWN_MS - (now - lastIssue)) / 1000;
            throw new IllegalArgumentException(Messages.get("cert_rate_limited", remaining));
        }
        byte[] entropy = new byte[32];
        random.nextBytes(entropy);
        String credential = "axontc_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        temporaryCredentials.put(credential, new TemporaryCredential(user,
            identity.certificate(), fingerprint, fromCertificate, System.currentTimeMillis() + 60_000L));
        lastCredentialIssuance.put(userKey, now);
        com.axonbase.server.AxonLog.log("info", "certificate_login", "credencial temporária emitida",
            Map.of("user", user, "store", certificateStore));
        return credential;
    }

    /** Retorna os segundos restantes do cooldown, ou 0 se puder emitir. */
    public long remainingCooldown(String user, String store) {
        String userKey = user + "@" + store;
        Long lastIssue = lastCredentialIssuance.get(userKey);
        if (lastIssue == null) return 0;
        long remaining = COOLDOWN_MS - (System.currentTimeMillis() - lastIssue);
        return remaining > 0 ? remaining / 1000 : 0;
    }

    private void cleanup() {
        temporaryCredentials.entrySet().removeIf(entry -> entry.getValue().expired());
        credentialLogins.values().removeIf(CredentialLogin::expired);
    }

    private static boolean matchesFingerprint(AuthCatalog.User identity, String fingerprint) {
        // Sem FINGERPRINT definido, qualquer certificado confiável do JKS vale.
        if (identity.fingerprint() == null || identity.fingerprint().isBlank()) {
            return true;
        }
        return java.util.Objects.equals(identity.fingerprint(), fingerprint);
    }

    /** O usuário tem FINGERPRINT definido e ele é o do certificado apresentado. */
    private static boolean pinned(AuthCatalog.User identity, String fingerprint) {
        return identity.fingerprint() != null && !identity.fingerprint().isBlank()
            && identity.fingerprint().equals(fingerprint);
    }

    private AuthCatalog.User pinnedUser(String name, String certificateStore, String fingerprint) {
        return users.catalog().users().stream()
            .filter(u -> u.name().equals(name) && u.certificateBased() && u.certificate().equals(certificateStore))
            .filter(u -> pinned(u, fingerprint))
            .findFirst()
            .orElse(null);
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
            credentialLogins.remove(pass);
            return null;
        }
        if (!credential.matches(user)) {
            return null;
        }
        AuthCatalog.User identity = users.catalog().user(user, namespace, database);
        if (identity == null || !identity.certificateBased()
            || !identity.certificate().equals(credential.certificate())) {
            return null;
        }
        boolean accepted = credential.identityFromCertificate()
            ? matchesFingerprint(identity, credential.fingerprint())
            : pinned(identity, credential.fingerprint());
        return accepted ? identity : null;
    }

    /** Verifica que un JWT sexa válido. Devolve {@code true} se válido. */
    public boolean authenticate(String token) {
        return claims(token) != null;
    }

    /** Claims verificados, ou {@code null} quando o token é inválido/expirado. */
    public Map<String, Object> claims(String token) {
        if (token != null && token.startsWith(ServiceTokenStore.PREFIX)) {
            return serviceClaims(token);
        }
        Map<String, Object> claims;
        try {
            claims = token == null ? null : verifiedClaims(token);
        } catch (RuntimeException e) {
            return null;
        }
        if (claims == null || !(claims.get("exp") instanceof Number exp)
            || exp.longValue() < System.currentTimeMillis() / 1000) {
            return null;
        }
        if (claims.get("jti") != null && revokedJwts.containsKey(String.valueOf(claims.get("jti")))) {
            return null;
        }
        if (claims.get("jti") != null) {
            TokenSession seen = tokenSessions.get(String.valueOf(claims.get("jti")));
            if (seen != null) {
                seen.lastSeenMs = System.currentTimeMillis();
            }
        }
        return claims;
    }

    /**
     * Verifica a assinatura do JWT, guardando o resultado por token: a mesma sessão manda o mesmo token em milhares de
     * requisições e o HMAC mais a leitura do JSON eram refeitos em cada uma (duas vezes, com o filtro de log). Só a
     * assinatura é guardada; expiração, revogação e escopo seguem sendo conferidos a cada chamada em {@link #claims}.
     */
    private Map<String, Object> verifiedClaims(String token) {
        Map<String, Object> hit = jwtCache.get(token);
        if (hit != null) {
            return hit;
        }
        Map<String, Object> verified = Jwt.verify(secret, token);
        if (verified != null) {
            if (jwtCache.size() >= JWT_CACHE_MAX) {
                jwtCache.clear();
            }
            jwtCache.put(token, Map.copyOf(verified));
        }
        return verified;
    }

    private static final int JWT_CACHE_MAX = 4096;
    private final java.util.concurrent.ConcurrentHashMap<String, Map<String, Object>> jwtCache =
        new java.util.concurrent.ConcurrentHashMap<>();

    private Map<String, Object> serviceClaims(String token) {
        ServiceTokenStore.Info info = serviceTokens.verify(token);
        if (info == null) {
            return null;
        }
        Map<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("id", info.name());
        claims.put("jti", info.id());
        claims.put("typ", "service");
        claims.put("scope", info.scope());
        if (info.ns() != null) {
            claims.put("ns", info.ns());
        }
        if (info.db() != null) {
            claims.put("db", info.db());
        }
        if (!info.roles().isEmpty()) {
            claims.put("roles", String.join(",", info.roles()));
        }
        claims.put("exp", info.expiresAt() / 1000);
        return claims;
    }

    // ------------------------------------------------------------------
    // inventário de sessões (tokens de acesso emitidos)
    // ------------------------------------------------------------------

    private static final int MAX_TRACKED_SESSIONS = 10_000;

    /** Um token de acesso emitido por /signin ou signin do WebSocket. HTTP não tem sessão no servidor: a sessão é o token. */
    public static final class TokenSession {
        private final String id;
        private final String user;
        private final String scope;
        private final String ns;
        private final String db;
        private final long issuedAtMs;
        private final long expiresAtMs;
        private volatile long lastSeenMs;

        TokenSession(String id, String user, String scope, String ns, String db, long issuedAtMs, long expiresAtMs) {
            this.id = id;
            this.user = user;
            this.scope = scope;
            this.ns = ns;
            this.db = db;
            this.issuedAtMs = issuedAtMs;
            this.expiresAtMs = expiresAtMs;
            this.lastSeenMs = issuedAtMs;
        }

        public String id() { return id; }
        public String user() { return user; }
        public String scope() { return scope; }
        public String ns() { return ns; }
        public String db() { return db; }
        public long issuedAtMs() { return issuedAtMs; }
        public long expiresAtMs() { return expiresAtMs; }
        public long lastSeenMs() { return lastSeenMs; }
    }

    private final Map<String, TokenSession> tokenSessions = new ConcurrentHashMap<>();

    private void trackSession(Map<String, Object> claims, long ttlMs) {
        long now = System.currentTimeMillis();
        tokenSessions.values().removeIf(t -> t.expiresAtMs() < now);
        if (tokenSessions.size() >= MAX_TRACKED_SESSIONS) {
            return;
        }
        String jti = String.valueOf(claims.get("jti"));
        tokenSessions.put(jti, new TokenSession(jti, String.valueOf(claims.get("id")),
            String.valueOf(claims.getOrDefault("scope", "ROOT")),
            claims.get("ns") == null ? null : String.valueOf(claims.get("ns")),
            claims.get("db") == null ? null : String.valueOf(claims.get("db")), now, now + ttlMs));
    }

    /** Sessões (tokens) ainda válidas, da mais antiga para a mais nova. */
    public java.util.List<TokenSession> sessions() {
        long now = System.currentTimeMillis();
        tokenSessions.values().removeIf(t -> t.expiresAtMs() < now);
        java.util.List<TokenSession> out = new java.util.ArrayList<>(tokenSessions.values());
        out.sort(java.util.Comparator.comparingLong(TokenSession::issuedAtMs));
        return out;
    }

    public int sessionCount() {
        return sessions().size();
    }

    public TokenSession session(String id) {
        return tokenSessions.get(id);
    }

    /** Encerra a sessão revogando o token. {@code false} se ela não existe (ou já expirou). */
    public boolean endSession(String id) {
        TokenSession t = tokenSessions.remove(id);
        if (t == null) {
            return false;
        }
        revokeJwt(id, t.expiresAtMs() / 1000);
        return true;
    }

    /** Tokens de serviço (criação, listagem e revogação). */
    public ServiceTokenStore serviceTokens() {
        return serviceTokens;
    }

    /** Revoga um JWT pelo {@code jti} até o instante {@code expSeconds} (epoch segundos) do próprio token. */
    public void revokeJwt(String jti, long expSeconds) {
        long now = System.currentTimeMillis() / 1000;
        revokedJwts.entrySet().removeIf(e -> e.getValue() < now);
        revokedJwts.put(jti, expSeconds);
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
    public synchronized String signup(String user, String pass, String namespace, String database) {
        if (user == null || user.isBlank()) {
            throw new IllegalArgumentException(Messages.get("auth_signup_user_invalid"));
        }
        if (namespace == null || namespace.isBlank() || database == null || database.isBlank()) {
            return null;
        }
        // signup só cria: trocar senha ou papéis de quem já existe é outra operação
        boolean taken = users.catalog().users().stream().anyMatch(u -> u.name().equals(user)
            && u.scope() == AuthCatalog.Scope.DATABASE
            && namespace.equals(u.namespace()) && database.equals(u.database()));
        if (taken) {
            throw new IllegalArgumentException(Messages.get("auth_signup_user_exists"));
        }
        users.catalog().defineUser(user, AuthCatalog.Scope.DATABASE, namespace, database,
            pass == null ? "" : pass, java.util.List.of(), java.util.List.of());
        return signin(user, pass == null ? "" : pass, namespace, database, null);
    }
}
