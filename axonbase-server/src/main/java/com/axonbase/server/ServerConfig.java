package com.axonbase.server;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Configuração de arranque: valores padrão, arquivo {@code axonbase.conf} e
 * variáveis {@code AXON_*}. O formato do arquivo é uma linha por chave:
 * {@code port = 8000}. Linhas vazias e comentários iniciados por {@code #} ou
 * {@code ;} são ignorados.
 */
public record ServerConfig(String path, String secret, String user, String password,
                            int port, String bind, boolean requireAuth, String clusterId,
                            String nodeId, String raftBind, String raftPeers) {

    public static final String DEFAULT_FILE = "axonbase.conf";

    public static ServerConfig defaults() {
        return new ServerConfig("memory", "axonbase-dev-secret", "root", "root",
            8000, "127.0.0.1", true, "", "", "", "");
    }

    /** Lê o arquivo (se existir) e aplica por cima as variáveis de ambiente. */
    public static ServerConfig load(Path file, Map<String, String> env) throws IOException {
        ServerConfig base = fromMap(read(file), defaults());
        return new ServerConfig(
            envOr(env, "AXON_PATH", base.path()),
            envOr(env, "AXON_SECRET", base.secret()),
            envOr(env, "AXON_USER", base.user()),
            envOr(env, "AXON_PASS", base.password()),
            intOr(env.get("AXON_PORT"), base.port()),
            envOr(env, "AXON_BIND", base.bind()),
            boolOr(env.get("AXON_REQUIRE_AUTH"), base.requireAuth()),
            envOr(env, "AXON_CLUSTER_ID", base.clusterId()),
            envOr(env, "AXON_NODE_ID", base.nodeId()),
            envOr(env, "AXON_RAFT_BIND", base.raftBind()),
            envOr(env, "AXON_RAFT_PEERS", base.raftPeers()));
    }

    private static ServerConfig fromMap(Map<String, String> values, ServerConfig defaults) {
        return new ServerConfig(
            value(values, "path", defaults.path()),
            value(values, "secret", defaults.secret()),
            value(values, "user", defaults.user()),
            value(values, "pass", value(values, "password", defaults.password())),
            intOr(values.get("port"), defaults.port()),
            value(values, "bind", defaults.bind()),
            boolOr(values.get("require_auth"), defaults.requireAuth()),
            value(values, "cluster_id", defaults.clusterId()),
            value(values, "node_id", defaults.nodeId()),
            value(values, "raft_bind", defaults.raftBind()),
            value(values, "raft_peers", defaults.raftPeers()));
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
                throw new IllegalArgumentException("configuração inválida em " + file + ":" + lineNo);
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

    private static int intOr(String raw, int fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw);
            if (value < 0 || value > 65535) {
                throw new IllegalArgumentException("porta fora do intervalo: " + raw);
            }
            return value;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("porta inválida: " + raw, e);
        }
    }

    private static boolean boolOr(String raw, boolean fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        return switch (raw.trim().toLowerCase()) {
            case "true", "1", "yes", "sim" -> true;
            case "false", "0", "no", "nao", "não" -> false;
            default -> throw new IllegalArgumentException("booleano inválido: " + raw);
        };
    }
}
