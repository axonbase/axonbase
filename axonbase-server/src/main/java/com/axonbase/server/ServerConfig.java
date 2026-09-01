package com.axonbase.server;

import com.axonbase.common.Messages;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public record ServerConfig(String path, String secret, String user, String password,
                             int port, String bind, boolean requireAuth, String clusterId,
                             String nodeId, String raftBind, String raftPeers,
                             long queryTimeout, long txnTimeout, int rateLimit,
                             String corsOrigins, String tlsCert, String tlsKey, String tlsCa,
                             long shutdownTimeout, int httpThreads, int httpQueue,
                            String s3Endpoint, String s3Bucket, String s3Prefix,
                            String s3Access, String s3Secret, long s3Interval,
                            String s3EncryptKey, int s3Retention,
                            int plainPort, String lang,
                            String aiProvider, String aiModel, String aiApiKey, String aiBaseUrl) {

    public static final String DEFAULT_FILE = "axonbase.conf";

    public static ServerConfig defaults() {
        return new ServerConfig("memory", "axonbase-dev-secret", "root", "root",
            8000, "127.0.0.1", true, "", "", "", "",
            30000L, 300000L, 0, "", "", "", "",
            10000L, 4, 100,
            "", "", "", "", "", 0L,
            "", 10, 0, "pt-BR",
            "", "", "", "");
    }

    public static ServerConfig load(Path file, Map<String, String> env) throws IOException {
        ServerConfig base = fromMap(read(file), defaults());
        return new ServerConfig(
            envOr(env, "AXON_PATH", base.path()),
            envOr(env, "AXON_SECRET", base.secret()),
            envOr(env, "AXON_USER", base.user()),
            envOr(env, "AXON_PASS", base.password()),
            portOr(env.get("AXON_PORT"), base.port()),
            envOr(env, "AXON_BIND", base.bind()),
            boolOr(env.get("AXON_REQUIRE_AUTH"), base.requireAuth()),
            envOr(env, "AXON_CLUSTER_ID", base.clusterId()),
            envOr(env, "AXON_NODE_ID", base.nodeId()),
            envOr(env, "AXON_RAFT_BIND", base.raftBind()),
            envOr(env, "AXON_RAFT_PEERS", base.raftPeers()),
            longOr(env.get("AXON_QUERY_TIMEOUT"), base.queryTimeout()),
            longOr(env.get("AXON_TXN_TIMEOUT"), base.txnTimeout()),
            intOr(env.get("AXON_RATE_LIMIT"), base.rateLimit()),
            envOr(env, "AXON_CORS_ORIGINS", base.corsOrigins()),
            envOr(env, "AXON_TLS_CERT", base.tlsCert()),
            envOr(env, "AXON_TLS_KEY", base.tlsKey()),
            envOr(env, "AXON_TLS_CA", base.tlsCa()),
            longOr(env.get("AXON_SHUTDOWN_TIMEOUT"), base.shutdownTimeout()),
            intOr(env.get("AXON_HTTP_THREADS"), base.httpThreads()),
            intOr(env.get("AXON_HTTP_QUEUE"), base.httpQueue()),
            envOr(env, "AXON_S3_ENDPOINT", base.s3Endpoint()),
            envOr(env, "AXON_S3_BUCKET", base.s3Bucket()),
            envOr(env, "AXON_S3_PREFIX", base.s3Prefix()),
            envOr(env, "AXON_S3_ACCESS", base.s3Access()),
            envOr(env, "AXON_S3_SECRET", base.s3Secret()),
            longOr(env.get("AXON_S3_INTERVAL"), base.s3Interval()),
            envOr(env, "AXON_S3_ENCRYPT_KEY", base.s3EncryptKey()),
            intOr(env.get("AXON_S3_RETENTION"), base.s3Retention()),
            intOr(env.get("AXON_PLAIN_PORT"), base.plainPort()),
            envOr(env, "AXON_LANG", base.lang()),
            envOr(env, "AXON_AI_PROVIDER", base.aiProvider()),
            envOr(env, "AXON_AI_MODEL", base.aiModel()),
            envOr(env, "AXON_AI_API_KEY", base.aiApiKey()),
            envOr(env, "AXON_AI_BASE_URL", base.aiBaseUrl()));
    }

    private static ServerConfig fromMap(Map<String, String> values, ServerConfig defaults) {
        return new ServerConfig(
            value(values, "path", defaults.path()),
            value(values, "secret", defaults.secret()),
            value(values, "user", defaults.user()),
            value(values, "pass", value(values, "password", defaults.password())),
            portOr(values.get("port"), defaults.port()),
            value(values, "bind", defaults.bind()),
            boolOr(values.get("require_auth"), defaults.requireAuth()),
            value(values, "cluster_id", defaults.clusterId()),
            value(values, "node_id", defaults.nodeId()),
            value(values, "raft_bind", defaults.raftBind()),
            value(values, "raft_peers", defaults.raftPeers()),
            longOr(values.get("query_timeout"), defaults.queryTimeout()),
            longOr(values.get("txn_timeout"), defaults.txnTimeout()),
            intOr(values.get("rate_limit"), defaults.rateLimit()),
            value(values, "cors_origins", defaults.corsOrigins()),
            value(values, "tls_cert", defaults.tlsCert()),
            value(values, "tls_key", defaults.tlsKey()),
            value(values, "tls_ca", defaults.tlsCa()),
            longOr(values.get("shutdown_timeout"), defaults.shutdownTimeout()),
            intOr(values.get("http_threads"), defaults.httpThreads()),
            intOr(values.get("http_queue"), defaults.httpQueue()),
            value(values, "s3_endpoint", defaults.s3Endpoint()),
            value(values, "s3_bucket", defaults.s3Bucket()),
            value(values, "s3_prefix", defaults.s3Prefix()),
            value(values, "s3_access", defaults.s3Access()),
            value(values, "s3_secret", defaults.s3Secret()),
            longOr(values.get("s3_interval"), defaults.s3Interval()),
            value(values, "s3_encrypt_key", defaults.s3EncryptKey()),
            intOr(values.get("s3_retention"), defaults.s3Retention()),
            intOr(values.get("plain_port"), defaults.plainPort()),
            value(values, "lang", defaults.lang()),
            value(values, "ai_provider", defaults.aiProvider()),
            value(values, "ai_model", defaults.aiModel()),
            value(values, "ai_api_key", defaults.aiApiKey()),
            value(values, "ai_base_url", defaults.aiBaseUrl()));
    }

    private static Map<String, String> read(Path file) throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return Map.of();
        }
        Map<String, String> values = new HashMap<>();
        int lineNo = 0;
        for (String raw : Files.readAllLines(file)) {
            lineNo++;
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 1) {
                throw new IllegalArgumentException(Messages.get("config_invalid", file, lineNo));
            }
            String key = line.substring(0, eq).trim().toLowerCase();
            String value = unquote(line.substring(eq + 1).trim());
            values.put(key, value);
        }
        return values;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && ((value.startsWith("\"") && value.endsWith("\""))
            || (value.startsWith("'") && value.endsWith("'")))) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static String value(Map<String, String> values, String key, String fallback) {
        String value = values.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String envOr(Map<String, String> env, String key, String fallback) {
        String value = env.get(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    private static long longOr(String raw, long fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(Messages.get("config_invalid_long", raw), e);
        }
    }

    private static int intOr(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(Messages.get("config_invalid_int", raw), e);
        }
    }

    private static int portOr(String raw, int fallback) {
        int value = intOr(raw, fallback);
        if (value < 0 || value > 65535) {
            throw new IllegalArgumentException(Messages.get("config_invalid_port", raw));
        }
        return value;
    }

    private static boolean boolOr(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase()) {
            case "true", "1", "yes", "sim" -> true;
            case "false", "0", "no", "nao", "não" -> false;
            default -> throw new IllegalArgumentException(Messages.get("config_invalid_bool", raw));
        };
    }
}
