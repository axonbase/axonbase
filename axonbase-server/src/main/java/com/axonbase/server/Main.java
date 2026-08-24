package com.axonbase.server;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.WalBackend;
import com.axonbase.core.storage.VersionedKvBackend;
import com.axonbase.core.cluster.ClusterConfig;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.TcpRaftCommitCoordinator;

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
            System.err.println("comando desconhecido: " + cmd);
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
                case "--config" -> i++; // já foi usado antes para carregar a base
                case "--no-auth" -> requireAuth = false;
                default -> System.err.println("flag ignorada: " + args[i]);
            }
        }

        KvBackend backend = "memory".equals(path)
            ? new MemoryBackend()
            : new WalBackend(path);
        ClusterRuntime cluster = null;
        if (!config.clusterId().isBlank()) {
            if (config.nodeId().isBlank() || config.raftBind().isBlank()) {
                throw new IllegalArgumentException("cluster_id requer node_id e raft_bind");
            }
            ClusterConfig clusterConfig = ClusterConfig.parse(config.nodeId(), config.clusterId(),
                config.raftBind(), config.raftPeers());
            cluster = new ClusterRuntime(clusterConfig, java.nio.file.Path.of(path + ".raft"),
                (VersionedKvBackend) backend);
        }
        Datastore ds = new Datastore(backend);
        ds.createDatabase("axonbase", "main");
        if (cluster != null) {
            ds.commitCoordinator(new TcpRaftCommitCoordinator(config.clusterId(), config.nodeId(),
                cluster.config().peers()), config.nodeId());
        }

        AxonServer server = AxonServer.start(ds, secret, port, user, pass, requireAuth, bind);
        if (cluster != null) {
            ClusterRuntime runtime = cluster;
            server.clusterStatus(() -> {
                var status = runtime.status();
                int members = runtime.config().peers().size() + 1;
                return new com.axonbase.core.cluster.ClusterStatusProvider.Status(status.nodeId(),
                    status.clusterId(), config.nodeId(), status.term(), 0, members, members / 2 + 1);
            });
        }
        System.out.println("AxonBase 0.1.0 iniciado en http://" + bind + ":" + port
            + " (storage=" + path + ")");
        System.out.println("Para detelo, Ctrl+C");
        Thread.currentThread().join();
    }

    private static String envOr(String key, String def) {
        String v = System.getenv(key);
        return v == null || v.isBlank() ? def : v;
    }

    private static void usage() {
        System.out.println("Uso: axonbase start [--config axonbase.conf] [--path memory|<arquivo>] [--port 8000]"
            + " [--secret <segredo>] [--user root] [--pass root] [--no-auth]");
    }
}
