package com.axonbase.core.security;

import com.axonbase.common.Messages;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Catálogo de identidades do AxonBase. Mantém somente hashes salgados de senha,
 * com escopo ROOT, NAMESPACE ou DATABASE. A persistência acompanha a do
 * catálogo geral do datastore.
 */
public final class AuthCatalog {

    public enum Scope { ROOT, NAMESPACE, DATABASE }

    public record User(String name, Scope scope, String namespace, String database,
                       String saltHex, String hashHex, String certificate, String fingerprint,
                       List<String> roles, List<String> dataRules, String auditName) {
        public User {
            if (certificate != null && (saltHex != null || hashHex != null)) {
                throw new IllegalArgumentException(Messages.get("auth_cert_user_password"));
            }
            if (certificate == null && (saltHex == null || hashHex == null)) {
                throw new IllegalArgumentException(Messages.get("auth_user_needs_salt"));
            }
            roles = roles == null ? List.of() : List.copyOf(roles);
            dataRules = dataRules == null ? List.of() : List.copyOf(dataRules);
        }

        public User(String name, Scope scope, String namespace, String database,
                    String saltHex, String hashHex, String certificate, String fingerprint,
                    List<String> roles, List<String> dataRules) {
            this(name, scope, namespace, database, saltHex, hashHex, certificate, fingerprint,
                roles, dataRules, null);
        }

        public boolean certificateBased() {
            return certificate != null;
        }
    }

    public record Access(String name, Scope scope, String namespace, String database) {
    }

    private static final String PEPPER = "axonbase::auth::v2";
    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, Access> accesses = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();

    /**
     * Cria ou substitui o usuário, com a senha convertida em hash antes de guardar.
     *
     * @return a identidade gravada, já com salt e hash, para que o chamador possa
     *         replicá-la sem manter a senha em texto puro
     */
    public User defineUser(String name, Scope scope, String namespace, String database,
                           String password, List<String> roles, List<String> dataRules) {
        return defineUser(name, scope, namespace, database, password, roles, dataRules, null);
    }

    public User defineUser(String name, Scope scope, String namespace, String database,
                           String password, List<String> roles, List<String> dataRules,
                           String auditName) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(Messages.get("auth_user_empty"));
        }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        String saltHex = HexFormat.of().formatHex(salt);
        String hashHex = hash(salt, password == null ? "" : password);
        User user = new User(name, scope, namespace, database, saltHex, hashHex, null, null,
            roles == null ? List.of() : List.copyOf(roles),
            dataRules == null ? List.of() : List.copyOf(dataRules), auditName);
        users.put(userKey(name, scope, namespace, database), user);
        return user;
    }

    /** Cria ou substitui um usuário autenticável exclusivamente por certificado. */
    public User defineCertificateUser(String name, Scope scope, String namespace, String database,
                                      String certificate, String fingerprint, List<String> roles,
                                      List<String> dataRules) {
        return defineCertificateUser(name, scope, namespace, database, certificate, fingerprint,
            roles, dataRules, null);
    }

    public User defineCertificateUser(String name, Scope scope, String namespace, String database,
                                      String certificate, String fingerprint, List<String> roles,
                                      List<String> dataRules, String auditName) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(Messages.get("auth_user_empty"));
        }
        if (certificate == null || certificate.isBlank()) {
            throw new IllegalArgumentException(Messages.get("auth_cert_empty"));
        }
        User user = new User(name, scope, namespace, database, null, null, certificate, fingerprint,
            roles == null ? List.of() : List.copyOf(roles),
            dataRules == null ? List.of() : List.copyOf(dataRules), auditName);
        users.put(userKey(name, scope, namespace, database), user);
        return user;
    }

    /** Cria o usuário se ele ainda não existir no escopo indicado. */
    public void ensureUser(String name, String password) {
        String key = userKey(name, Scope.ROOT, null, null);
        if (!users.containsKey(key)) {
            defineUser(name, Scope.ROOT, null, null, password, List.of("OWNER"), List.of());
        }
    }

    /** Procura a identidade mais específica que se aplique ao ns/db indicado. */
    public User verify(String name, String password, String namespace, String database) {
        for (Scope scope : List.of(Scope.DATABASE, Scope.NAMESPACE, Scope.ROOT)) {
            String ns = scope == Scope.ROOT ? null : (scope == Scope.NAMESPACE ? namespace : namespace);
            String db = scope == Scope.DATABASE ? database : null;
            User user = users.get(userKey(name, scope, ns, db));
            if (user != null && !user.certificateBased() && constantTimeEquals(user.hashHex(), hash(
                HexFormat.of().parseHex(user.saltHex()), password == null ? "" : password))) {
                return user;
            }
        }
        return null;
    }

    /** Procura a identidade mais específica sem validar uma senha. */
    public User user(String name, String namespace, String database) {
        for (Scope scope : List.of(Scope.DATABASE, Scope.NAMESPACE, Scope.ROOT)) {
            String ns = scope == Scope.ROOT ? null : (scope == Scope.NAMESPACE ? namespace : namespace);
            String db = scope == Scope.DATABASE ? database : null;
            User user = users.get(userKey(name, scope, ns, db));
            if (user != null) {
                return user;
            }
        }
        return null;
    }

    /**
     * Procura uma identidade de certificado com o nome e truststore dados, em
     * qualquer escopo. Usado na emissão de credencial temporária, que não fica
     * amarrada a um namespace/banco específico.
     */
    public User certificateUser(String name, String certificateStore, String fingerprint) {
        return users.values().stream()
            .filter(user -> user.name().equals(name))
            .filter(AuthCatalog.User::certificateBased)
            .filter(user -> user.certificate().equals(certificateStore))
            .filter(user -> fingerprint == null || fingerprint.isBlank()
                || user.fingerprint() == null || user.fingerprint().isBlank()
                || user.fingerprint().equals(fingerprint))
            .findFirst()
            .orElse(null);
    }

    public void defineAccess(String name, Scope scope, String namespace, String database) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(Messages.get("auth_access_empty"));
        }
        accesses.put(accessKey(name, scope, namespace, database),
            new Access(name, scope, namespace, database));
    }

    /** O access precisa aplicar-se ao namespace/banco solicitado. */
    public Access access(String name, String namespace, String database) {
        if (name == null || name.isBlank()) {
            return null;
        }
        for (Scope scope : List.of(Scope.DATABASE, Scope.NAMESPACE, Scope.ROOT)) {
            Access access = accesses.get(accessKey(name, scope, namespace, database));
            if (access != null) {
                return access;
            }
        }
        return null;
    }

    public List<User> users() {
        return users.values().stream().sorted(java.util.Comparator.comparing(User::name)).toList();
    }

    public List<Access> accesses() {
        return accesses.values().stream().sorted(java.util.Comparator.comparing(Access::name)).toList();
    }

    /** Remove um usuário em qualquer escopo compatível com ns/db. */
    public boolean removeUser(String name, String namespace, String database) {
        for (Scope scope : List.of(Scope.DATABASE, Scope.NAMESPACE, Scope.ROOT)) {
            String ns = scope == Scope.ROOT ? null : (scope == Scope.NAMESPACE ? namespace : namespace);
            String db = scope == Scope.DATABASE ? database : null;
            if (users.remove(userKey(name, scope, ns, db)) != null) {
                return true;
            }
        }
        return false;
    }

    /** Remove um access method em qualquer escopo compatível com ns/db. */
    public boolean removeAccess(String name, String namespace, String database) {
        for (Scope scope : List.of(Scope.DATABASE, Scope.NAMESPACE, Scope.ROOT)) {
            String ns = scope == Scope.ROOT ? null : (scope == Scope.NAMESPACE ? namespace : namespace);
            String db = scope == Scope.DATABASE ? database : null;
            if (accesses.remove(accessKey(name, scope, ns, db)) != null) {
                return true;
            }
        }
        return false;
    }

    /** Restaura uma identidade já protegida por hash, sem reprocessar a senha. */
    public void restoreUser(User user) {
        users.put(userKey(user.name(), user.scope(), user.namespace(), user.database()), user);
    }

    /** Restaura um access method persistido. */
    public void restoreAccess(Access access) {
        accesses.put(accessKey(access.name(), access.scope(), access.namespace(), access.database()), access);
    }

    public void clear() {
        users.clear();
        accesses.clear();
    }

    private static String userKey(String name, Scope scope, String ns, String db) {
        String normalizedNs = scope == Scope.ROOT ? "" : nullToEmpty(ns);
        String normalizedDb = scope == Scope.DATABASE ? nullToEmpty(db) : "";
        return name + "\u0000" + scope + "\u0000" + normalizedNs + "\u0000" + normalizedDb;
    }

    private static String accessKey(String name, Scope scope, String ns, String db) {
        return userKey(name, scope, ns, db);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String hash(byte[] salt, String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(salt);
            digest.update(PEPPER.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(password.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(Messages.get("auth_hash_unavailable"), e);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8));
    }
}
