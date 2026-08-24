package com.axonbase.core.security;

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
                       String saltHex, String hashHex, List<String> roles) {
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
                           String password, List<String> roles) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("nome de usuário não pode ser vazio");
        }
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        String saltHex = HexFormat.of().formatHex(salt);
        String hashHex = hash(salt, password == null ? "" : password);
        User user = new User(name, scope, namespace, database, saltHex, hashHex,
            roles == null ? List.of() : List.copyOf(roles));
        users.put(userKey(name, scope, namespace, database), user);
        return user;
    }

    /** Cria o usuário se ele ainda não existir no escopo indicado. */
    public void ensureUser(String name, String password) {
        String key = userKey(name, Scope.ROOT, null, null);
        if (!users.containsKey(key)) {
            defineUser(name, Scope.ROOT, null, null, password, List.of("OWNER"));
        }
    }

    /** Procura a identidade mais específica que se aplique ao ns/db indicado. */
    public User verify(String name, String password, String namespace, String database) {
        for (Scope scope : List.of(Scope.DATABASE, Scope.NAMESPACE, Scope.ROOT)) {
            User user = users.get(userKey(name, scope, namespace, database));
            if (user != null && constantTimeEquals(user.hashHex(), hash(
                HexFormat.of().parseHex(user.saltHex()), password == null ? "" : password))) {
                return user;
            }
        }
        return null;
    }

    public void defineAccess(String name, Scope scope, String namespace, String database) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("nome de access não pode ser vazio");
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
            throw new IllegalStateException("SHA-256 não disponível", e);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return MessageDigest.isEqual(left.getBytes(StandardCharsets.UTF_8),
            right.getBytes(StandardCharsets.UTF_8));
    }
}
