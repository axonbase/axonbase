package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.journal.PitrJournal;
import com.axonbase.common.Messages;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.core.storage.WalBackend;
import com.axonbase.core.storage.VersionedKvBackend;
import com.axonbase.core.cluster.ClockHooks;
import com.axonbase.core.cluster.ClusterConfig;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.TcpRaftCommitCoordinator;
import com.axonbase.core.shard.backup.ShardBackupNode;
import com.axonbase.core.shard.backup.ShardJournalHooks;
import com.axonbase.core.backup.S3SyncManager;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Punto de entrada da CLI do AxonBase.
 * <pre>
 *   axonbase start [--bind 127.0.0.1] [--port 8000] [--path memory|&lt;arquivo&gt;]
 *                 [--secret &lt;segredo&gt;] [--user root] [--pass root]
 * </pre>
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        String cmd = args[0];
        if ("gateway".equals(cmd)) {
            GatewayMain.run(System.getenv());
            return;
        }
        if ("restore-shards".equals(cmd)) {
            ShardRestoreMain.run(java.util.Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        if (!"start".equals(cmd)) {
            System.err.println(Messages.get("cli_unknown_command", cmd));
            usage();
            return;
        }

        String configPath = envOr("AXON_CONFIG", ServerConfig.DEFAULT_FILE);
        for (int i = 1; i < args.length; i++) {
            if ("--config".equals(args[i]) && i + 1 < args.length) {
                configPath = args[++i];
            }
        }
        ServerConfig config = ServerConfig.load(java.nio.file.Path.of(configPath), System.getenv());
        Messages.setLanguage(config.lang());
        String path = config.path();
        String secret = config.secret();
        int port = config.port();
        String bind = config.bind();
        boolean requireAuth = config.requireAuth();

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--path", "-p" -> path = args[++i];
                case "--secret" -> secret = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--bind", "-b" -> bind = args[++i];
                case "--config" -> i++;
                case "--no-auth" -> requireAuth = false;
                default -> System.err.println(Messages.get("cli_ignored_flag", args[i]));
            }
        }

        // Rebuild the config with CLI overrides while preserving all other settings.
        config = new ServerConfig(path, secret, config.user(), config.password(), port, bind, requireAuth,
            config.clusterId(), config.nodeId(), config.raftBind(), config.raftPeers(),
            config.queryTimeout(), config.txnTimeout(), config.rateLimit(),
            config.corsOrigins(), config.tlsCert(), config.tlsKey(), config.tlsCa(),
            config.shutdownTimeout(), config.httpThreads(), config.httpQueue(),
            config.s3Endpoint(), config.s3Bucket(), config.s3Prefix(),
            config.s3Access(), config.s3Secret(), config.s3Interval(),
            config.s3EncryptKey(), config.s3Retention(),
            config.plainPort(), config.lang(),
            config.aiProvider(), config.aiModel(), config.aiApiKey(), config.aiBaseUrl(),
            config.auditEnabled(), config.auditUserFrom(),
            config.auditSelect(), config.auditMaxBody());

        String insecure = ServerConfig.insecureDefaultsProblem(config, System.getenv());
        if (insecure != null) {
            System.err.println(insecure);
            System.exit(2);
            return;
        }

        // Réplica de leitura: recebe o log, não vota. A base pode vir de um backup físico de um membro.
        boolean learner = "true".equalsIgnoreCase(System.getenv("AXON_RAFT_LEARNER"));
        String bootstrapBackup = System.getenv("AXON_RAFT_BOOTSTRAP_BACKUP");
        if (learner && (config.clusterId().isBlank() || path.equals("memory"))) {
            throw new IllegalArgumentException(Messages.get("server_learner_requires"));
        }
        if (learner && bootstrapBackup != null && !bootstrapBackup.isBlank()) {
            String directory = path.startsWith("rocksdb:") ? path.substring(8) : path;
            com.axonbase.core.cluster.LearnerBootstrap
                .restore(java.nio.file.Path.of(bootstrapBackup.trim()), java.nio.file.Path.of(directory))
                .ifPresent(applied -> System.out.println(
                    Messages.get("server_learner_restored", applied.index(), bootstrapBackup.trim())));
        }

        KvBackend backend = switch (path) {
            case "memory" -> new MemoryBackend();
            case String s when s.startsWith("rocksdb:") -> new RocksDbBackend(s.substring(8));
            default -> new RocksDbBackend(path);
        };

        // o journal entra antes do Datastore, para arquivar também as escritas da subida
        PitrSettings pitrSettings = PitrSettings.fromEnvironment();
        // em cluster quem arquiva é o apply do Raft (journal de shard, ligado abaixo)
        boolean shardJournal = pitrSettings.enabled() && !config.clusterId().isBlank()
            && backend instanceof RocksDbBackend;
        PitrJournal journal = shardJournal ? null : startPitr(pitrSettings, backend, config.clusterId());

        // Create Datastore before default databases so S3 restore can replay into it.
        Datastore ds = new Datastore(backend);
        ds.aiProvider(new com.axonbase.core.audit.AiProviderClient(
            config.aiProvider(), config.aiModel(), config.aiApiKey(), config.aiBaseUrl()));
        ds.auditConfig(config.auditEnabled(), config.auditUserFrom(),
            config.auditSelect(), config.auditMaxBody());

        // Try S3 restore BEFORE creating default databases (they would make the
        // backend non-empty and skip the restore).
        if (!config.s3Bucket().isBlank() && !config.s3Endpoint().isBlank()) {
            java.util.function.Supplier<Long> commitIndex = () -> null;
            var s3 = new S3SyncManager(ds, config, commitIndex);
            try {
                if (backend.keysWithPrefix("").isEmpty()) {
                    boolean restored = s3.restoreFromS3();
                    if (restored) {
                        System.out.println(Messages.get("server_s3_restored"));
                    } else {
                        System.out.println(Messages.get("server_s3_empty"));
                        ds.createDatabase("axonbase", "main");
                        ds.createDatabase("app", "main");
                    }
                }
            } catch (Exception e) {
                System.out.println(Messages.get("server_s3_empty"));
            }
        } else {
            ds.createDatabase("axonbase", "main");
            ds.createDatabase("app", "main");
        }

        ClusterRuntime cluster = null;
        ShardBackupNode shardBackup = null;
        if (!config.clusterId().isBlank()) {
            if (config.nodeId().isBlank() || config.raftBind().isBlank()) {
                throw new IllegalArgumentException(Messages.get("server_cluster_missing"));
            }
            if (!(backend instanceof VersionedKvBackend)) {
                throw new IllegalArgumentException(Messages.get("server_cluster_backend"));
            }
            ClusterConfig clusterConfig = ClusterConfig.parse(config.nodeId(), config.clusterId(),
                config.raftBind(), config.raftPeers());
            if (learner) {
                clusterConfig = clusterConfig.asLearner();
            }
            cluster = new ClusterRuntime(clusterConfig, java.nio.file.Path.of(path + ".raft"),
                (VersionedKvBackend) backend);
            // logo depois de construir: o relógio precisa ver a cauda do log antes de carimbar qualquer lote
            if (shardJournal) {
                RocksDbBackend rocks = (RocksDbBackend) backend;
                ShardJournalHooks hooks = ShardJournalHooks.open(rocks.clock(), pitrSettings.directoryFor(rocks),
                    pitrSettings.options());
                cluster.hooks(hooks);
                shardBackup = new ShardBackupNode(cluster, rocks, hooks, shardBackupDir(rocks),
                    java.time.Duration.ofSeconds(60));
                Runtime.getRuntime().addShutdownHook(new Thread(hooks::close, "ax-shard-journal-close"));
            } else {
                cluster.hooks(new ClockHooks(clockFor(backend)));
            }
        }
        if (cluster != null) {
            cluster.appliedBatchListener(ds.appliedBatchListener());
            ds.commitCoordinator(new TcpRaftCommitCoordinator(cluster), config.nodeId());
        }

        AxonServer server = AxonServer.start(ds, secret, config);
        server.pitr(journal);
        server.shardBackup(shardBackup);
        if (cluster != null) {
            server.clusterStatus(cluster);
        }
        String webhookUrl = System.getenv("AXON_WEBHOOK_URL");
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            server.webhook(webhookUrl.trim(), System.getenv("AXON_WEBHOOK_SECRET"));
        }
        server.metricsPerDatabase("true".equalsIgnoreCase(System.getenv("AXON_METRICS_PER_DATABASE")));
        server.metricsRequireAuth("true".equalsIgnoreCase(System.getenv("AXON_METRICS_REQUIRE_AUTH")));
        AxonLog.log("info", "server_started", Messages.get("server_started", BuildInfo.version(), bind, port, path));
        if (cluster != null) {
            System.out.println(Messages.get("server_cluster", config.clusterId(), config.nodeId(),
                config.raftBind(), cluster.quorum(), cluster.members()));
        }
        System.out.println(Messages.get("server_stopped_hint"));

        // Start periodic S3 synchronization when S3 is configured.
        if (!config.s3Bucket().isBlank() && !config.s3Endpoint().isBlank()) {
            java.util.function.Supplier<Long> commitIndex =
                cluster != null ? cluster::commitIndex : () -> null;
            var s3 = new S3SyncManager(ds, config, commitIndex);
            server.backups(s3);
            s3.start();

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    s3.backupNow();
                } catch (Exception e) {
                    System.err.println(Messages.get("server_s3_shutdown_error", e.getMessage()));
                }
            }));
        }

        Thread.currentThread().join();
    }

    /**
     * Liga o journal de PITR quando pedido, em nó único com RocksDB. No cluster os lotes vêm do log Raft e a
     * restauração a um instante ainda não existe (o backup físico é só de nó único).
     */
    static PitrJournal startPitr(PitrSettings settings, KvBackend backend, String clusterId) {
        if (!settings.enabled()) {
            return null;
        }
        if (!(backend instanceof RocksDbBackend rocks) || !clusterId.isBlank()) {
            AxonLog.log("warn", "pitr_unavailable", Messages.get("pitr_unavailable"));
            return null;
        }
        // quem fecha é o desligamento do servidor, depois de atender as requisições em curso
        return PitrJournal.attach(rocks, settings.directoryFor(rocks), settings.options());
    }

    /** Onde os backups físicos de shard ficam: {@code AXON_SHARD_BACKUP_DIR}, ou ao lado dos dados. */
    private static Path shardBackupDir(RocksDbBackend rocks) {
        // dentro do diretório de dados o nome precisa começar com ponto, como o do journal
        return Path.of(envOr("AXON_SHARD_BACKUP_DIR", rocks.directory().resolve(".shard-backups").toString()));
    }

    /** O relógio do armazenamento já guarda em disco um teto do que emite; sem RocksDB, o do sistema basta. */
    static HybridClock clockFor(KvBackend backend) {
        return backend instanceof RocksDbBackend rocks ? rocks.clock() : HybridClock.system();
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    private static void usage() {
        System.out.println(Messages.get("server_usage"));
    }
}
