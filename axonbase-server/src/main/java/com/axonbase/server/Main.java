package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.common.Messages;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.core.storage.WalBackend;
import com.axonbase.core.storage.VersionedKvBackend;
import com.axonbase.core.cluster.ClusterConfig;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.TcpRaftCommitCoordinator;
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
        String user = config.user();
        String pass = config.password();
        int port = config.port();
        String bind = config.bind();
        boolean requireAuth = config.requireAuth();

        for (int i = 1; i < args.length; i++) {
            switch (args[i]) {
                case "--path", "-p" -> path = args[++i];
                case "--secret" -> secret = args[++i];
                case "--user", "-u" -> user = args[++i];
                case "--pass", "--password" -> pass = args[++i];
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--bind", "-b" -> bind = args[++i];
                case "--config" -> i++;
                case "--no-auth" -> requireAuth = false;
                default -> System.err.println(Messages.get("cli_ignored_flag", args[i]));
            }
        }

        // Rebuild the config with CLI overrides while preserving all other settings.
        config = new ServerConfig(path, secret, user, pass, port, bind, requireAuth,
            config.clusterId(), config.nodeId(), config.raftBind(), config.raftPeers(),
            config.queryTimeout(), config.txnTimeout(), config.rateLimit(),
            config.corsOrigins(), config.tlsCert(), config.tlsKey(), config.tlsCa(),
            config.shutdownTimeout(), config.httpThreads(), config.httpQueue(),
            config.s3Endpoint(), config.s3Bucket(), config.s3Prefix(),
            config.s3Access(), config.s3Secret(), config.s3Interval(),
            config.s3EncryptKey(), config.s3Retention(),
            config.plainPort(), config.lang(),
            config.aiProvider(), config.aiModel(), config.aiApiKey(), config.aiBaseUrl());

        KvBackend backend = switch (path) {
            case "memory" -> new MemoryBackend();
            case String s when s.startsWith("rocksdb:") -> new RocksDbBackend(s.substring(8));
            default -> new RocksDbBackend(path);
        };

        // Create Datastore before default databases so S3 restore can replay into it.
        Datastore ds = new Datastore(backend);
        ds.aiProvider(new com.axonbase.core.audit.AiProviderClient(
            config.aiProvider(), config.aiModel(), config.aiApiKey(), config.aiBaseUrl()));

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
        if (!config.clusterId().isBlank()) {
            if (config.nodeId().isBlank() || config.raftBind().isBlank()) {
                throw new IllegalArgumentException(Messages.get("server_cluster_missing"));
            }
            if (!(backend instanceof VersionedKvBackend)) {
                throw new IllegalArgumentException(Messages.get("server_cluster_backend"));
            }
            ClusterConfig clusterConfig = ClusterConfig.parse(config.nodeId(), config.clusterId(),
                config.raftBind(), config.raftPeers());
            cluster = new ClusterRuntime(clusterConfig, java.nio.file.Path.of(path + ".raft"),
                (VersionedKvBackend) backend);
        }
        if (cluster != null) {
            cluster.appliedBatchListener(ds.appliedBatchListener());
            ds.commitCoordinator(new TcpRaftCommitCoordinator(cluster), config.nodeId());
        }

        AxonServer server = AxonServer.start(ds, secret, config);
        if (cluster != null) {
            server.clusterStatus(cluster);
        }
        System.out.println(Messages.get("server_started", "0.1.0", bind, port, path));
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

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    private static void usage() {
        System.out.println(Messages.get("server_usage"));
    }
}
