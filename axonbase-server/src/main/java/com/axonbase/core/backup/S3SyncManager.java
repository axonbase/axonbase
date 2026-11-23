package com.axonbase.core.backup;

import com.axonbase.core.engine.Datastore;
import com.axonbase.server.ServerConfig;

import java.io.ByteArrayInputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Gerenciador de backup S3: período, incremental, criptografia e retenção.
 *
 * <p>Usa {@link PipedOutputStream} + {@link PipedInputStream} para bombear o dump
 * do {@link Datastore} para o upload S3 em pedaços de 8 KB sem materializar uma
 * segunda cópia do payload em RAM. Quando uma senha de criptografia é fornecida
 * ({@code AXON_S3_ENCRYPT_KEY}), o dump é encriptado com AES-256-GCM antes do
 * upload e decriptado no download.</p>
 *
 * <p>A retenção mantém apenas os N backups mais recentes (config {@code s3_retention},
 * padrão 10). O nome do arquivo segue o padrão
 * {@code {prefix}/cluster-{yyyyMMdd-HHmmss}.axbak}.</p>
 */
public final class S3SyncManager implements AutoCloseable {

    private static final int CHUNK_SIZE = 8192;
    private static final Pattern BACKUP_NAME =
        Pattern.compile("(^|/)cluster-[0-9]{8}-[0-9]{6}\\.axbak$");
    private static final DateTimeFormatter STAMP =
        DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final Datastore datastore;
    private final RemoteBackupStore store;
    private final String bucket;
    private final String prefix;
    private final long intervalMillis;
    private final int retention;
    private final String encryptKey;
    private final Supplier<Long> commitIndexSupplier;
    private final Supplier<String> stampSupplier;
    private final Object lock = new Object();

    private ScheduledExecutorService scheduler;
    private volatile boolean started;
    private long lastBackedUpIndex;
    private boolean lastWasFull;

    // ---- estado e histórico (API de gestão) ----
    private static final String META_PREFIX = "backup|";
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean manualPending = new AtomicBoolean();
    private final AtomicLong runsOk = new AtomicLong();
    private final AtomicLong runsFailed = new AtomicLong();
    private volatile Long lastSuccessAt;
    private volatile Long lastErrorAt;
    private volatile String lastError;
    private volatile long lastDurationMs;
    private volatile long lastSizeBytes;
    private volatile String lastType;
    private volatile Long nextRunAt;
    private volatile BiConsumer<String, Map<String, Object>> listener = (type, data) -> { };
    private final java.util.concurrent.ExecutorService manualExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "s3-backup-manual");
        t.setDaemon(true);
        return t;
    });

    /** Retrato do estado dos backups. */
    public record Status(boolean running, Long lastSuccessAtMillis, Long lastErrorAtMillis, String lastError,
                         long lastDurationMs, long lastSizeBytes, String lastType, Long nextRunAtMillis) {
    }

    /** Backup existente no armazenamento remoto. {@code sizeBytes} e {@code type} são nulos se desconhecidos. */
    public record BackupEntry(String name, Long sizeBytes, long createdAtMillis, String type) {
    }

    // ------------------------------------------------------------------
    // Construtores
    // ------------------------------------------------------------------

    /**
     * Construtor principal que deriva as configurações do {@link ServerConfig}.
     */
    public S3SyncManager(Datastore datastore, ServerConfig config,
                         Supplier<Long> commitIndexSupplier) {
        this(datastore,
            new S3BackupClient(config.s3Endpoint(), "", config.s3Access(), config.s3Secret(), false),
            config.s3Bucket(),
            normalizePrefix(config.s3Prefix()),
            config.s3Interval() > 0 ? config.s3Interval() : 60_000L,
            config.s3Retention() <= 0 ? 10 : config.s3Retention(),
            config.s3EncryptKey(),
            commitIndexSupplier,
            S3SyncManager::nowStamp);
    }

    /**
     * Construtor de injeção para testes (público, sem dependência de ServerConfig).
     */
    public S3SyncManager(Datastore datastore, RemoteBackupStore store,
                         String bucket, String prefix, long intervalMillis, int retention,
                         String encryptKey, Supplier<Long> commitIndexSupplier,
                         Supplier<String> stampSupplier) {
        this.datastore = datastore;
        this.store = store;
        this.bucket = bucket;
        this.prefix = prefix;
        this.intervalMillis = intervalMillis;
        this.retention = retention;
        this.encryptKey = encryptKey;
        this.commitIndexSupplier = commitIndexSupplier;
        this.stampSupplier = stampSupplier;
    }

    // ------------------------------------------------------------------
    // Ciclo de vida
    // ------------------------------------------------------------------

    /**
     * Inicia o backup periódico com o intervalo configurado.
     */
    public void start() {
        synchronized (lock) {
            if (started) return;
            started = true;
            scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "s3-backup");
                t.setDaemon(true);
                return t;
            });
            nextRunAt = System.currentTimeMillis() + intervalMillis;
            scheduler.scheduleWithFixedDelay(() -> backupNow(false),
                intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        }
    }

    /**
     * Para o backup periódico.
     */
    public void stop() {
        synchronized (lock) {
            started = false;
            if (scheduler != null) {
                scheduler.shutdown();
                try {
                    scheduler.awaitTermination(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                scheduler = null;
            }
        }
    }

    @Override
    public void close() {
        stop();
        manualExecutor.shutdownNow();
    }

    /** Indica se o backup remoto está configurado (sempre verdadeiro para uma instância). */
    public boolean configured() {
        return true;
    }

    // ------------------------------------------------------------------
    // Backup / Restore
    // ------------------------------------------------------------------

    /**
     * Executa um backup agora: gera o dump, encripta (se configurado), faz upload
     * via streaming chunked, e aplica rotação.
     */
    public boolean backupNow() {
        return backupNow(false);
    }

    /** Como {@link #backupNow()}; com {@code forceFull} ignora o modo incremental. */
    public boolean backupNow(boolean forceFull) {
        synchronized (lock) {
            running.set(true);
            long startedAt = System.currentTimeMillis();
            long t0 = System.nanoTime();
            try {
                Long idx = commitIndexSupplier.get();
                long current = idx == null ? 0 : idx;

                String dump;
                boolean incremental = false;
                if (!forceFull && current > 0 && datastore.incrementalSupported()
                    && lastBackedUpIndex > 0 && current > lastBackedUpIndex) {
                    dump = datastore.backupIncremental(lastBackedUpIndex);
                    incremental = true;
                } else {
                    dump = datastore.backupCluster();
                }

                String stamp = stampSupplier.get();
                String key = (prefix.isEmpty() ? "" : prefix + "/") + "cluster-" + stamp + ".axbak";

                byte[] plain = dump.getBytes(StandardCharsets.UTF_8);
                byte[] payload;
                if (encryptKey != null && !encryptKey.isBlank()) {
                    payload = S3Encryption.encryptBytes(plain, encryptKey);
                } else {
                    payload = plain;
                }

                // Streaming via pipe: escreve em chunks para o PipedOutputStream;
                // o PipedInputStream alimenta o upload S3 com setChunkedStreamingMode(8192).
                var pump = Executors.newSingleThreadExecutor();
                try (var pis = new PipedInputStream()) {
                    var pos = new PipedOutputStream(pis);
                    var future = pump.submit(() -> {
                        try (var in = new ByteArrayInputStream(payload);
                             var out = pos) {
                            byte[] buf = new byte[CHUNK_SIZE];
                            int read;
                            while ((read = in.read(buf)) >= 0) {
                                out.write(buf, 0, read);
                            }
                            out.flush();
                        } catch (java.io.IOException e) {
                            throw new RuntimeException(e);
                        }
                        return null;
                    });
                    store.upload(bucket, key, pis);
                    future.get(30, TimeUnit.SECONDS);
                } finally {
                    pump.shutdownNow();
                }

                lastBackedUpIndex = current;
                lastWasFull = !incremental;

                rotate(key);
                String type = incremental ? "incremental" : "full";
                recordSuccess(key, payload.length, type, startedAt, (System.nanoTime() - t0) / 1_000_000L);
                return true;
            } catch (Exception e) {
                System.err.println("Erro no backup S3: " + e.getMessage());
                recordFailure(e, startedAt, (System.nanoTime() - t0) / 1_000_000L);
                return false;
            } finally {
                running.set(false);
                if (started) {
                    nextRunAt = System.currentTimeMillis() + intervalMillis;
                }
            }
        }
    }

    private void recordSuccess(String key, long size, String type, long startedAt, long durationMs) {
        long now = System.currentTimeMillis();
        lastSuccessAt = now;
        lastSizeBytes = size;
        lastType = type;
        lastDurationMs = durationMs;
        lastError = null;
        runsOk.incrementAndGet();
        String name = baseName(key);
        datastore.metaPut(META_PREFIX + name, (size + "|" + type + "|" + now).getBytes(StandardCharsets.UTF_8));
        listener.accept("backup.finished", Map.of("name", name, "sizeBytes", size, "type", type,
            "durationMs", durationMs));
    }

    private void recordFailure(Exception e, long startedAt, long durationMs) {
        lastErrorAt = System.currentTimeMillis();
        lastError = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        lastDurationMs = durationMs;
        runsFailed.incrementAndGet();
        listener.accept("backup.failed", Map.of("error", lastError, "durationMs", durationMs));
    }

    /** Registra o receptor de eventos {@code backup.finished} e {@code backup.failed}. */
    public void listener(BiConsumer<String, Map<String, Object>> listener) {
        this.listener = listener == null ? (type, data) -> { } : listener;
    }

    /**
     * Dispara um backup em segundo plano.
     *
     * @return o id da execução, ou {@code null} se já há um backup em andamento
     */
    public String triggerAsync(boolean forceFull) {
        if (running.get() || !manualPending.compareAndSet(false, true)) {
            return null;
        }
        String runId = java.util.UUID.randomUUID().toString();
        manualExecutor.execute(() -> {
            try {
                backupNow(forceFull);
            } finally {
                manualPending.set(false);
            }
        });
        return runId;
    }

    public Status status() {
        return new Status(running.get() || manualPending.get(), lastSuccessAt, lastErrorAt, lastError,
            lastDurationMs, lastSizeBytes, lastType, nextRunAt);
    }

    public long runsOk() {
        return runsOk.get();
    }

    public long runsFailed() {
        return runsFailed.get();
    }

    /** Backups no armazenamento remoto, do mais antigo para o mais novo. */
    public List<BackupEntry> listBackupEntries() {
        List<BackupEntry> out = new ArrayList<>();
        for (String key : listBackups()) {
            String name = baseName(key);
            Long size = null;
            String type = null;
            var meta = datastore.metaGet(META_PREFIX + name);
            if (meta.isPresent()) {
                String[] parts = new String(meta.get(), StandardCharsets.UTF_8).split("\\|");
                if (parts.length >= 2) {
                    try {
                        size = Long.parseLong(parts[0]);
                    } catch (NumberFormatException ignored) {
                        size = null;
                    }
                    type = parts[1];
                }
            }
            out.add(new BackupEntry(name, size, stampMillis(name), type));
        }
        return out;
    }

    private static String baseName(String key) {
        int slash = key.lastIndexOf('/');
        return slash < 0 ? key : key.substring(slash + 1);
    }

    private static long stampMillis(String name) {
        try {
            String stamp = name.substring("cluster-".length(), name.length() - ".axbak".length());
            return LocalDateTime.parse(stamp, STAMP).atZone(java.time.ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /**
     * Restaura o estado a partir dos backups S3.
     *
     * <p>Baixa todos os backups em ordem cronológica, decripta (se encriptados), mescla
     * as linhas (controle + chaves) num único dump e chama {@link Datastore#restoreCluster}.
     * Backups mais recentes sobrescrevem chaves de backups anteriores.</p>
     */
    public boolean restoreFromS3() {
        return restoreKeys(listBackups());
    }

    /**
     * Restaura até o backup indicado (inclusive), aplicando os anteriores em ordem porque os
     * incrementais dependem deles.
     *
     * @return {@code false} se o nome não existe ou não há dados
     */
    public boolean restoreTo(String name) {
        List<String> all = listBackups();
        int idx = -1;
        for (int i = 0; i < all.size(); i++) {
            if (baseName(all.get(i)).equals(name)) {
                idx = i;
            }
        }
        return idx >= 0 && restoreKeys(all.subList(0, idx + 1));
    }

    private boolean restoreKeys(List<String> keys) {
        synchronized (lock) {
            try {
                if (keys.isEmpty()) {
                    return false;
                }

                var merged = new StringBuilder(S3Encryption.PLAIN_HEADER).append('\n');
                boolean any = false;

                for (String file : keys) {
                    byte[] raw = store.download(bucket, file);
                    if (raw == null || raw.length == 0) continue;

                    String text;
                    byte[] decrypted = S3Encryption.decryptBytes(raw, encryptKey);
                    text = new String(decrypted, StandardCharsets.UTF_8);

                    int nl = text.indexOf('\n');
                    if (nl < 0) continue;
                    String body = text.substring(nl + 1);
                    for (String line : body.split("\n", -1)) {
                        if (line.isBlank()) continue;
                        merged.append(line).append('\n');
                        any = true;
                    }
                }

                if (!any) return false;
                datastore.restoreCluster(merged.toString());
                return true;
            } catch (Exception e) {
                System.err.println("Erro ao restaurar do S3: " + e.getMessage());
                return false;
            }
        }
    }

    // ------------------------------------------------------------------
    // Rotação
    // ------------------------------------------------------------------

    private void rotate(String uploadedKey) {
        if (retention <= 0) return;
        try {
            List<String> backups = listBackups();
            if (backups.size() <= retention) return;
            int toDelete = backups.size() - retention;
            for (int i = 0; i < toDelete; i++) {
                String old = backups.get(i);
                if (!old.equals(uploadedKey)) {
                    store.deleteObject(bucket, old);
                }
            }
        } catch (Exception e) {
            System.err.println("Erro na rotação de backups S3: " + e.getMessage());
        }
    }

    private List<String> listBackups() {
        List<String> all;
        try {
            all = store.listObjects(bucket, listPrefix());
        } catch (Exception e) {
            return List.of();
        }
        var backups = new ArrayList<String>();
        for (String k : all) {
            if (isBackupName(k)) {
                backups.add(k);
            }
        }
        backups.sort(Comparator.naturalOrder());
        return backups;
    }

    private String listPrefix() {
        return prefix.isEmpty() ? "cluster-" : prefix + "/cluster-";
    }

    private static boolean isBackupName(String key) {
        return key != null && BACKUP_NAME.matcher(key).find();
    }

    private static String nowStamp() {
        return LocalDateTime.now().format(STAMP);
    }

    private static String normalizePrefix(String p) {
        if (p == null || p.isBlank()) return "";
        String s = p;
        while (s.startsWith("/")) s = s.substring(1);
        while (s.endsWith("/")) s = s.substring(0, s.length() - 1);
        return s;
    }
}