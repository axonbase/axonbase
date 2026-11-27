package com.axonbase.core.partition;

import com.axonbase.common.Messages;
import com.axonbase.core.backup.S3BackupClient;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.partition.cold.BloomFilter;
import com.axonbase.core.partition.cold.ColdCache;
import com.axonbase.core.partition.cold.ColdFailure;
import com.axonbase.core.partition.cold.ColdHolds;
import com.axonbase.core.partition.cold.ColdStore;
import com.axonbase.core.partition.cold.FileSystemColdStore;
import com.axonbase.core.partition.cold.S3ColdStore;
import com.axonbase.core.storage.KvBackend;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiPredicate;

/**
 * A camada fria de um motor: o armazenamento de objetos, o cache local e os prazos. Todos os nós de um cluster
 * precisam apontar para o mesmo armazenamento.
 */
public final class ColdTier implements AutoCloseable {

    /** Padrões: 35 dias de carência (o máximo da restauração a um instante) e coleta de lixo de hora em hora. */
    public record Settings(long cacheBytes, Duration grace, Duration gcInterval, Duration logicalHold) {
        public static Settings defaults() {
            return new Settings(1024L << 20, Duration.ofDays(35), Duration.ofHours(1), Duration.ofDays(35));
        }

        public Settings withGrace(Duration value) {
            return new Settings(cacheBytes, value, gcInterval, logicalHold);
        }

        public Settings withGcInterval(Duration value) {
            return new Settings(cacheBytes, grace, value, logicalHold);
        }

        public Settings withCacheBytes(long value) {
            return new Settings(value, grace, gcInterval, logicalHold);
        }
    }

    /** Sem armazenamento: nada vai para a camada fria, e ler uma partição fria é um erro. */
    public static final ColdTier NONE = new ColdTier(null, null, Settings.defaults());

    private final ColdStore store;
    private final ColdCache cache;
    private final Settings settings;
    /** Identifica este motor nos bloqueios; muda a cada processo, e o bloqueio antigo vence sozinho. */
    private final String engineId = UUID.randomUUID().toString();
    private final ConcurrentMap<String, BloomFilter> filters = new ConcurrentHashMap<>();
    private final ColdRecords records;

    private ColdTier(ColdStore store, ColdCache cache, Settings settings) {
        this.store = store;
        this.cache = cache;
        this.settings = settings;
        this.records = store == null ? ColdRecords.UNCONFIGURED : new Reader();
    }

    public static ColdTier of(ColdStore store, Path cacheParent, Settings settings) {
        return new ColdTier(store, new ColdCache(store, cacheParent, settings.cacheBytes()), settings);
    }

    /**
     * Lê {@code AXON_COLD_DIR} ou {@code AXON_COLD_S3} ({@code s3://bucket/prefixo?endpoint=...&access=...}). Sem
     * nenhum dos dois, a camada fria fica desligada; com os dois, o motor recusa subir.
     */
    public static ColdTier fromEnvironment(Map<String, String> env) {
        String directory = value(env, "AXON_COLD_DIR");
        String s3 = value(env, "AXON_COLD_S3");
        if (directory != null && s3 != null) {
            throw new IllegalArgumentException("defina AXON_COLD_DIR ou AXON_COLD_S3, não os dois");
        }
        if (directory == null && s3 == null) {
            return NONE;
        }
        ColdStore store;
        if (directory != null) {
            store = new FileSystemColdStore(Path.of(directory));
        } else {
            S3BackupClient client = S3BackupClient.fromUri(s3);
            store = new S3ColdStore(client, client.bucket(s3), client.key(s3));
        }
        Settings d = Settings.defaults();
        long grace = number(env, "AXON_COLD_GC_GRACE_DAYS", d.grace().toDays());
        String pitr = value(env, "AXON_PITR");
        if (pitr != null && (pitr.equalsIgnoreCase("on") || pitr.equalsIgnoreCase("true"))) {
            // a restauração a um instante alcança objetos de partições removidas há até a retenção do journal
            grace = Math.max(grace, number(env, "AXON_PITR_RETENTION_DAYS", 7));
        }
        Settings settings = new Settings(
            number(env, "AXON_COLD_CACHE_MB", d.cacheBytes() >> 20) << 20,
            Duration.ofDays(grace),
            Duration.ofSeconds(number(env, "AXON_COLD_GC_INTERVAL_SECONDS", d.gcInterval().toSeconds())),
            Duration.ofDays(number(env, "AXON_COLD_LOGICAL_HOLD_DAYS", d.logicalHold().toDays())));
        String cache = value(env, "AXON_COLD_CACHE_DIR");
        return of(store, Path.of(cache != null ? cache : System.getProperty("java.io.tmpdir")), settings);
    }

    private static String value(Map<String, String> env, String name) {
        String raw = env.get(name);
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static long number(Map<String, String> env, String name, long fallback) {
        String raw = value(env, name);
        if (raw == null) {
            return fallback;
        }
        try {
            return Math.max(0, Long.parseLong(raw));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(name + " precisa ser um número: " + raw, e);
        }
    }

    public boolean configured() {
        return store != null;
    }

    public ColdStore store() {
        require();
        return store;
    }

    public ColdCache cache() {
        require();
        return cache;
    }

    public ColdHolds holds() {
        return new ColdHolds(store());
    }

    public Settings settings() {
        return settings;
    }

    public String engineId() {
        return engineId;
    }

    public ColdRecords records() {
        return records;
    }

    private void require() {
        if (store == null) {
            throw ColdFailure.error(Messages.get("cold_not_configured"));
        }
    }

    /**
     * Antes de mexer num índice: as linhas frias da tabela precisam estar ao alcance, senão a construção apagaria
     * entradas que não conseguiria refazer. Confere a presença dos objetos, sem baixá-los.
     *
     * @throws com.axonbase.common.AxonError marcado com {@link ColdFailure} quando algum não está ao alcance
     */
    public void requireReadable(Database db, String table) {
        if (db.catalog().partitioning(table) == null) {
            return;
        }
        for (PartitionDescriptor partition : PartitionStore.list(db, db.records(), table)) {
            if (!partition.coldData()) {
                continue;
            }
            require();
            boolean found;
            try {
                found = store.exists(partition.object());
            } catch (IOException | RuntimeException e) {
                throw ColdFailure.error(Messages.get("cold_unavailable", partition.object(), e.getMessage()));
            }
            if (!found) {
                throw ColdFailure.error(Messages.get("cold_unavailable", partition.object(), "o objeto não existe"));
            }
        }
    }

    @Override
    public void close() {
        if (cache != null) {
            cache.close();
        }
    }

    private final class Reader implements ColdRecords {
        @Override
        public Optional<byte[]> get(Database db, KvBackend kv, String table, PartitionDescriptor partition,
                                    String recordKey) {
            BloomFilter filter = filters.get(partition.object());
            if (filter == null) {
                var raw = PartitionStore.peek(kv,
                    PartitionKeys.bloomKey(db.ns(), db.db(), table, partition.start()));
                if (raw.isPresent()) {
                    filter = BloomFilter.decode(raw.get());
                    filters.put(partition.object(), filter);
                }
            }
            if (filter != null && !filter.mightContain(recordKey)) {
                return Optional.empty();
            }
            return cache.open(partition.object()).get(recordKey);
        }

        @Override
        public boolean scan(Database db, String table, PartitionDescriptor partition, String keyPrefix,
                            BiPredicate<String, byte[]> visitor) {
            return cache.open(partition.object()).scan(keyPrefix, visitor);
        }
    }
}
