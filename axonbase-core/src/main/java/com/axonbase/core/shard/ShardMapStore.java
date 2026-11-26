package com.axonbase.core.shard;

import com.axonbase.core.storage.VersionConflictException;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.LockSupport;
import java.util.function.UnaryOperator;

/**
 * O plano de controle: o mapa dos shards gravado como o registro único de uma tabela comum no grupo de metadados. A troca é por
 * comparação de época numa transação, então duas mudanças concorrentes nunca se sobrepõem: a segunda relê e refaz.
 */
public final class ShardMapStore {

    /** Namespace reservado do plano de controle. */
    public static final String NAMESPACE = "__axon_meta";
    private static final String DATABASE = "shards";
    private static final String TABLE = "shardmap";
    private static final int ATTEMPTS = 200;

    private final ShardClient meta;

    public ShardMapStore(ShardClient meta) {
        this.meta = meta;
    }

    public Optional<ShardMap> load() {
        AxonValue found = execute("SELECT * FROM " + TABLE, Map.of());
        if (found.isArray()) {
            if (found.asArray().isEmpty()) {
                return Optional.empty();
            }
            found = found.asArray().get(0);
        }
        return found.isObject() && found.asObject().containsKey("body")
            ? Optional.of(ShardMap.fromValue(AxonJson.parseDocument(found.asObject().get("body").asString())))
            : Optional.empty();
    }

    public ShardMap require() {
        return load().orElseThrow(
            () -> new ShardException(ShardException.Code.STALE_MAP, ShardMessages.get("shard_map_missing")));
    }

    /** Grava o mapa inicial se ainda não houver um; devolve o que vale. */
    public ShardMap bootstrap(ShardMap initial) {
        Optional<ShardMap> existing = load();
        if (existing.isPresent()) {
            return existing.get();
        }
        execute("DEFINE TABLE " + TABLE + " SCHEMALESS", Map.of());
        // o índice único sobre um valor fixo deixa existir um registro só: a segunda criação concorrente falha
        execute("DEFINE INDEX shardmap_one ON TABLE " + TABLE + " COLUMNS singleton UNIQUE", Map.of());
        try {
            atomic("CREATE " + TABLE + " SET singleton = 'map', epoch = $epoch, body = $body", variables(initial));
        } catch (RuntimeException lost) {
            // outro gateway criou primeiro; sem mapa gravado, o erro era outro
            return load().orElseThrow(() -> lost);
        }
        return require();
    }

    /**
     * Troca o mapa só se a época gravada ainda for {@code expectedEpoch}.
     *
     * @return {@code false} quando outro gravou antes
     */
    public boolean compareAndSet(long expectedEpoch, ShardMap next) {
        if (next.epoch() != expectedEpoch + 1) {
            throw new IllegalArgumentException("a época nova precisa ser " + (expectedEpoch + 1) + ": " + next.epoch());
        }
        Map<String, AxonValue> vars = new java.util.LinkedHashMap<>(variables(next));
        vars.put("expected", AxonValue.num(expectedEpoch));
        try {
            AxonValue changed = atomic("UPDATE " + TABLE + " SET epoch = $epoch, body = $body WHERE epoch = $expected",
                vars);
            return changed.isArray() && !changed.asArray().isEmpty();
        } catch (VersionConflictException lost) {
            return false;
        }
    }

    /** Aplica a mudança sobre o mapa mais novo, refazendo quando outro grava no meio. */
    public ShardMap update(UnaryOperator<ShardMap> change) {
        for (int attempt = 1; ; attempt++) {
            ShardMap current = require();
            ShardMap next = change.apply(current);
            if (next.equals(current)) {
                return current;
            }
            if (compareAndSet(current.epoch(), next)) {
                return next;
            }
            if (attempt >= ATTEMPTS) {
                throw new ShardException(ShardException.Code.STALE_MAP,
                    ShardMessages.get("shard_map_invalid", "mudanças concorrentes demais"));
            }
            LockSupport.parkNanos(ThreadLocalRandom.current().nextLong(1_000_000L * Math.min(attempt, 20)));
        }
    }

    private static Map<String, AxonValue> variables(ShardMap map) {
        return Map.of("epoch", AxonValue.num(map.epoch()), "body", AxonValue.str(AxonJson.write(map.toValue())));
    }

    private AxonValue execute(String sql, Map<String, AxonValue> vars) {
        return meta.execute(ShardRequest.of(NAMESPACE, DATABASE, sql).withVars(vars)).value();
    }

    private AxonValue atomic(String sql, Map<String, AxonValue> vars) {
        return meta.execute(ShardRequest.of(NAMESPACE, DATABASE, sql).withVars(vars).atomic()).value();
    }
}
