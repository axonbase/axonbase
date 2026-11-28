package com.axonbase.core.shard;

import java.time.Duration;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * A cópia do mapa dos shards que o gateway usa, <b>nunca mais velha que a validade</b>, contada do começo da leitura.
 * Sem conseguir renovar, recusa: é essa garantia que permite à movimentação de slots saber que, passado o prazo, nenhum
 * gateway decide mais pelo mapa anterior.
 */
final class ShardMapCache {

    private final Supplier<ShardMap> source;
    private final long ttlNanos;
    private final LongSupplier nanos;
    private ShardMap map;
    private long readAt;

    ShardMapCache(Supplier<ShardMap> source, Duration ttl, LongSupplier nanos) {
        this.source = source;
        this.ttlNanos = ttl.toNanos();
        this.nanos = nanos;
    }

    synchronized ShardMap get() {
        if (map == null || nanos.getAsLong() - readAt >= ttlNanos) {
            reload();
        }
        return map;
    }

    /** Lê de novo agora (depois de o próprio gateway mudar o mapa, ou de uma recusa por mapa velho). */
    synchronized ShardMap reload() {
        long started = nanos.getAsLong();
        try {
            map = source.get();
        } catch (ShardException e) {
            map = null;
            throw e;
        } catch (RuntimeException e) {
            map = null;
            throw new ShardException(ShardException.Code.STALE_MAP,
                ShardMessages.get("shard_map_unreadable", e.getMessage()), e);
        }
        readAt = started;
        return map;
    }
}
