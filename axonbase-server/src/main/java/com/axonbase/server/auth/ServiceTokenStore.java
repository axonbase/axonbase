package com.axonbase.server.auth;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tokens de serviço ({@code axsvc_<id>_<segredo>}) para agentes e integrações. O segredo aparece
 * uma única vez, na criação; o servidor guarda apenas o SHA-256 dele. Cada token tem id (que
 * funciona como {@code jti}), papéis, escopo e expiração, e pode ser revogado.
 */
public final class ServiceTokenStore {

    public static final String PREFIX = "axsvc_";
    private static final String META_PREFIX = "svctoken|";
    private static final long TOUCH_PERSIST_MS = 60_000L;

    /** Metadados públicos de um token (nunca o segredo nem o hash). */
    public record Info(String id, String name, String scope, String ns, String db, List<String> roles,
                       long createdAt, long expiresAt, Long lastUsedAt, boolean revoked) {
    }

    /** Resultado da criação: o token completo só existe aqui. */
    public record Created(String id, String token, long expiresAt) {
    }

    private static final class Entry {
        final Info base;
        final String secretHash;
        volatile long lastUsedAt;
        volatile boolean revoked;
        volatile long lastPersistedAt;

        Entry(Info base, String secretHash, long lastUsedAt, boolean revoked) {
            this.base = base;
            this.secretHash = secretHash;
            this.lastUsedAt = lastUsedAt;
            this.revoked = revoked;
        }

        Info info() {
            return new Info(base.id(), base.name(), base.scope(), base.ns(), base.db(), base.roles(),
                base.createdAt(), base.expiresAt(), lastUsedAt == 0 ? null : lastUsedAt, revoked);
        }
    }

    private final Map<String, Entry> tokens = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private volatile Datastore persistence;

    /** Liga a persistência e carrega os tokens já gravados. */
    public void persistTo(Datastore ds) {
        this.persistence = ds;
        for (String key : ds.metaKeys(META_PREFIX)) {
            ds.metaGet(key).ifPresent(raw -> {
                Entry e = decode(AxonJson.decode(raw));
                if (e != null) {
                    tokens.putIfAbsent(e.base.id(), e);
                }
            });
        }
    }

    /**
     * Cria um token. {@code expiresInDays} menor ou igual a zero cria um token já expirado
     * (útil só em testes); passe um valor positivo em uso normal.
     */
    public Created create(String name, String scope, String ns, String db, List<String> roles,
                          int expiresInDays) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name");
        }
        String id = randomHex(6);
        String secret = randomUrl(32);
        long now = System.currentTimeMillis();
        long expires = now + expiresInDays * 86_400_000L;
        if (expiresInDays <= 0) {
            expires = now - 1000;
        }
        Info info = new Info(id, name, scope == null ? "ROOT" : scope, ns, db,
            roles == null ? List.of() : List.copyOf(roles), now, expires, null, false);
        Entry entry = new Entry(info, hash(secret), 0, false);
        tokens.put(id, entry);
        persist(entry);
        return new Created(id, PREFIX + id + "_" + secret, expires);
    }

    /** Verifica o token apresentado e devolve seus metadados, ou {@code null} se inválido. */
    public Info verify(String presented) {
        if (presented == null || !presented.startsWith(PREFIX)) {
            return null;
        }
        String rest = presented.substring(PREFIX.length());
        int sep = rest.indexOf('_');
        if (sep < 1) {
            return null;
        }
        Entry e = tokens.get(rest.substring(0, sep));
        if (e == null || e.revoked || e.base.expiresAt() < System.currentTimeMillis()) {
            return null;
        }
        String candidate = hash(rest.substring(sep + 1));
        if (!MessageDigest.isEqual(candidate.getBytes(StandardCharsets.US_ASCII),
            e.secretHash.getBytes(StandardCharsets.US_ASCII))) {
            return null;
        }
        long now = System.currentTimeMillis();
        e.lastUsedAt = now;
        if (now - e.lastPersistedAt > TOUCH_PERSIST_MS) {
            persist(e);
        }
        return e.info();
    }

    public List<Info> list() {
        return tokens.values().stream().map(Entry::info)
            .sorted(Comparator.comparingLong(Info::createdAt)).toList();
    }

    /** Revoga o token. Devolve {@code false} quando o id não existe. */
    public boolean revoke(String id) {
        Entry e = tokens.get(id);
        if (e == null) {
            return false;
        }
        e.revoked = true;
        persist(e);
        return true;
    }

    // ---- persistência ----

    private void persist(Entry e) {
        Datastore ds = persistence;
        if (ds == null) {
            return;
        }
        e.lastPersistedAt = System.currentTimeMillis();
        Map<String, AxonValue> m = new LinkedHashMap<>();
        m.put("id", AxonValue.str(e.base.id()));
        m.put("name", AxonValue.str(e.base.name()));
        m.put("scope", AxonValue.str(e.base.scope()));
        m.put("ns", AxonValue.str(e.base.ns() == null ? "" : e.base.ns()));
        m.put("db", AxonValue.str(e.base.db() == null ? "" : e.base.db()));
        List<AxonValue> roles = new ArrayList<>();
        e.base.roles().forEach(r -> roles.add(AxonValue.str(r)));
        m.put("roles", AxonValue.array(roles));
        m.put("created_at", AxonValue.num(e.base.createdAt()));
        m.put("expires_at", AxonValue.num(e.base.expiresAt()));
        m.put("last_used_at", AxonValue.num(e.lastUsedAt));
        m.put("revoked", AxonValue.bool(e.revoked));
        m.put("hash", AxonValue.str(e.secretHash));
        ds.metaPut(META_PREFIX + e.base.id(), AxonJson.encode(AxonValue.object(m)));
    }

    private static Entry decode(AxonValue v) {
        try {
            if (v == null || !v.isObject()) {
                return null;
            }
            Map<String, AxonValue> o = v.asObject();
            List<String> roles = new ArrayList<>();
            o.get("roles").asArray().forEach(r -> roles.add(r.asString()));
            String ns = o.get("ns").asString();
            String db = o.get("db").asString();
            Info info = new Info(o.get("id").asString(), o.get("name").asString(), o.get("scope").asString(),
                ns.isEmpty() ? null : ns, db.isEmpty() ? null : db, roles,
                o.get("created_at").asLong(), o.get("expires_at").asLong(), null, false);
            return new Entry(info, o.get("hash").asString(), o.get("last_used_at").asLong(),
                o.get("revoked").asBool());
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String randomHex(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private String randomUrl(int bytes) {
        byte[] b = new byte[bytes];
        random.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String hash(String secret) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(secret.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
