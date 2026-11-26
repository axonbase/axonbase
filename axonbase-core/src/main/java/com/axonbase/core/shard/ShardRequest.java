package com.axonbase.core.shard;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.value.AxonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uma mensagem do gateway a um shard.
 *
 * @param atomic        roda o script inteiro numa transação (o bloco {@code BEGIN ... COMMIT} sem as duas instruções)
 * @param partial       executa um único {@code SELECT} em modo parcial, para o gateway fundir
 * @param filter        slots que o shard pode devolver (só durante uma movimentação), ou {@code null}
 * @param stamp         carimbo do relógio híbrido de quem envia
 * @param authorization cabeçalho {@code Authorization} do cliente, repassado ao shard; {@code null} sem autenticação
 */
public record ShardRequest(String namespace, String database, String sql, Map<String, AxonValue> vars,
                           boolean atomic, boolean partial, SlotFilter filter, HlcTimestamp stamp,
                           String authorization) {

    /** Pede as definições do catálogo, na ordem em que podem ser reaplicadas (para subir um shard novo). */
    public static final String DEFINITIONS = "__axon_definitions";

    public ShardRequest {
        vars = vars == null ? Map.of() : java.util.Collections.unmodifiableMap(new LinkedHashMap<>(vars));
        stamp = stamp == null ? HlcTimestamp.NONE : stamp;
    }

    public static ShardRequest of(String namespace, String database, String sql) {
        return new ShardRequest(namespace, database, sql, Map.of(), false, false, null, HlcTimestamp.NONE, null);
    }

    public static ShardRequest definitions() {
        return of(null, null, DEFINITIONS);
    }

    public ShardRequest withSql(String text) {
        return new ShardRequest(namespace, database, text, vars, atomic, partial, filter, stamp, authorization);
    }

    public ShardRequest withVars(Map<String, AxonValue> values) {
        return new ShardRequest(namespace, database, sql, values, atomic, partial, filter, stamp, authorization);
    }

    public ShardRequest atomic() {
        return new ShardRequest(namespace, database, sql, vars, true, partial, filter, stamp, authorization);
    }

    public ShardRequest partial(SlotFilter slots) {
        return new ShardRequest(namespace, database, sql, vars, atomic, true, slots, stamp, authorization);
    }

    public ShardRequest withStamp(HlcTimestamp value) {
        return new ShardRequest(namespace, database, sql, vars, atomic, partial, filter, value, authorization);
    }

    public ShardRequest withAuthorization(String header) {
        return new ShardRequest(namespace, database, sql, vars, atomic, partial, filter, stamp, header);
    }
}
