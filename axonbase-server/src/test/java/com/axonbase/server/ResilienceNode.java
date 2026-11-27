package com.axonbase.server;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Agente de uma máquina para os portões da E5. Sobe, derruba e troca o servidor de verdade
 * ({@code com.axonbase.server.Main}) como um processo à parte, e roda a ferramenta de DR ao lado dele. O controlador
 * ({@link ResilienceGates}) manda tudo por HTTP.
 *
 * <p><strong>Só para uma rede isolada de benchmark:</strong> quem alcança a porta de controle e conhece o token
 * ({@code AXON_GATE_TOKEN}, obrigatório) sobe processos nesta máquina, e o servidor sobe sem autenticação. Nada é
 * apagado: cada base nova usa um diretório novo.</p>
 *
 * <p>Argumentos: {@code --id n1 --advertise <ip> --data <dir> [--lib <dir>] [--new-lib <dir>] [--offset 0]}. Portas:
 * controle 8100, HTTP (HyperText Transfer Protocol) 8000 e Raft 9000, somadas ao {@code offset}. As variáveis
 * {@code AXON_S3_*} do agente valem para a ferramenta de DR.</p>
 */
public final class ResilienceNode implements AutoCloseable {

    static final int CONTROL_PORT = 8100;
    static final int HTTP_PORT = 8000;
    static final int RAFT_PORT = 9000;
    static final String TOKEN_HEADER = "Axon-Gate-Token";
    /** Variáveis do servidor que o controlador pode passar na subida. */
    private static final List<String> SERVER_SETTINGS = List.of("AXON_QOS", "AXON_QOS_ANALYTICAL_CONCURRENCY",
        "AXON_QOS_ANALYTICAL_CPU_PERCENT", "AXON_QOS_ANALYTICAL_QUEUE", "AXON_PITR", "AXON_PITR_SEGMENT_SECONDS",
        "AXON_CLUSTER_HTTP_PEERS", "AXON_DRAIN_GRACE_MS", "AXON_DRAIN_TIMEOUT_MS", "AXON_RAFT_LOG_SYNC",
        "AXON_STORAGE_SYNC");

    private final String id;
    private final String advertise;
    private final Path data;
    private final String lib;
    private final String newLib;
    private final int offset;
    private final String token;
    private final HttpServer control;
    private Process server;
    private Process archiver;
    private Path run;
    private Path restored;
    private int runs;

    ResilienceNode(Map<String, String> options, String token) throws IOException {
        this.id = options.get("id");
        this.advertise = options.get("advertise");
        this.data = Files.createDirectories(Path.of(options.get("data")).toAbsolutePath());
        this.lib = options.containsKey("lib") ? classpathOf(options.get("lib"))
            : System.getProperty("java.class.path");
        this.newLib = options.containsKey("new-lib") ? classpathOf(options.get("new-lib")) : lib;
        this.offset = Integer.parseInt(options.getOrDefault("offset", "0"));
        this.token = token;
        this.control = HttpServer.create(new InetSocketAddress(CONTROL_PORT + offset), 64);
        control.createContext("/", this::serve);
        control.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        control.start();
    }

    private static String classpathOf(String directory) {
        return Path.of(directory).toAbsolutePath() + "/*";
    }

    public static void main(String[] args) throws Exception {
        String token = System.getenv("AXON_GATE_TOKEN");
        Map<String, String> options = options(args, 0);
        if (token == null || token.isBlank() || !options.containsKey("id") || !options.containsKey("advertise")
            || !options.containsKey("data")) {
            System.err.println("uso: AXON_GATE_TOKEN=... ResilienceNode --id n1 --advertise <ip> --data <dir> "
                + "[--lib <dir>] [--new-lib <dir>] [--offset 0]");
            System.exit(2);
        }
        ResilienceNode node = new ResilienceNode(options, token);
        Runtime.getRuntime().addShutdownHook(new Thread(node::close));
        System.out.println("E5 agent id=" + node.id + " control=" + (CONTROL_PORT + node.offset));
        Thread.currentThread().join();
    }

    static Map<String, String> options(String[] args, int from) {
        Map<String, String> options = new HashMap<>();
        for (int i = from; i + 1 < args.length; i += 2) {
            options.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        return options;
    }

    private void serve(HttpExchange exchange) throws IOException {
        try (exchange) {
            if (!token.equals(exchange.getRequestHeaders().getFirst(TOKEN_HEADER))) {
                reply(exchange, 401, "token inválido");
                return;
            }
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            try {
                reply(exchange, 200, route(exchange.getRequestURI().getPath(), query));
            } catch (IllegalStateException refused) {
                reply(exchange, 409, String.valueOf(refused.getMessage()));
            } catch (Exception failure) {
                failure.printStackTrace();
                reply(exchange, 500, failure.toString());
            }
        }
    }

    private synchronized String route(String path, Map<String, String> query) throws Exception {
        return switch (path) {
            case "/info" -> "id=" + id + " http=" + advertise + ":" + (HTTP_PORT + offset) + " raft=" + advertise
                + ":" + (RAFT_PORT + offset) + " running=" + (server != null && server.isAlive());
            case "/start" -> start(query);
            case "/kill" -> end(true);
            case "/stop" -> end(false);
            case "/archive/start" -> startArchiver(query);
            case "/archive/stop" -> stopArchiver();
            case "/restore" -> restore(query);
            default -> throw new IllegalStateException("rota desconhecida: " + path);
        };
    }

    /**
     * {@code data=new} começa uma base vazia, {@code data=restored} usa a última restauração e {@code data=keep}
     * (padrão) reinicia sobre a mesma. {@code build=new} usa as bibliotecas de {@code --new-lib}.
     */
    private String start(Map<String, String> query) throws IOException {
        if (server != null && server.isAlive()) {
            throw new IllegalStateException("o servidor já está de pé");
        }
        switch (query.getOrDefault("data", "keep")) {
            case "new" -> run = newRun();
            case "restored" -> {
                if (restored == null) {
                    throw new IllegalStateException("nenhuma restauração neste nó");
                }
                run = restored;
            }
            default -> {
                if (run == null) {
                    run = newRun();
                }
            }
        }
        String classpath = query.getOrDefault("build", "old").equals("new") ? newLib : lib;
        ProcessBuilder builder = java(classpath, "com.axonbase.server.Main", List.of("start"));
        Map<String, String> env = builder.environment();
        env.put("AXON_PATH", run.resolve("db").toString());
        env.put("AXON_BIND", "0.0.0.0");
        env.put("AXON_PORT", Integer.toString(HTTP_PORT + offset));
        env.put("AXON_REQUIRE_AUTH", "false");
        env.put("AXON_CLUSTER_ID", "gate");
        env.put("AXON_NODE_ID", id);
        env.put("AXON_RAFT_BIND", advertise + ":" + (RAFT_PORT + offset));
        env.put("AXON_RAFT_PEERS", query.getOrDefault("peers", ""));
        SERVER_SETTINGS.stream().filter(query::containsKey).forEach(name -> env.put(name, query.get(name)));
        builder.redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(run.resolve("server.log").toFile()));
        server = builder.start();
        return "E5 node=" + id + " started dir=" + run;
    }

    private Path newRun() throws IOException {
        return Files.createDirectories(data.resolve("run-" + System.currentTimeMillis() + "-" + ++runs));
    }

    private ProcessBuilder java(String classpath, String main, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String option : System.getenv().getOrDefault("JAVA_OPTS", "-Xmx2g").trim().split("\\s+")) {
            if (!option.isEmpty()) {
                command.add(option);
            }
        }
        command.add("-cp");
        command.add(classpath);
        command.add(main);
        command.addAll(args);
        return new ProcessBuilder(command).directory((run == null ? data : run).toFile());
    }

    /** {@code kill -9} com {@code force}; senão o desligamento normal (SIGTERM). */
    private String end(boolean force) throws InterruptedException {
        if (server == null) {
            return "E5 node=" + id + " already stopped";
        }
        if (force) {
            server.destroyForcibly();
        } else {
            server.destroy();
        }
        if (!server.waitFor(60, TimeUnit.SECONDS)) {
            server.destroyForcibly().waitFor(30, TimeUnit.SECONDS);
        }
        server = null;
        return "E5 node=" + id + (force ? " killed" : " stopped");
    }

    private String startArchiver(Map<String, String> query) throws Exception {
        stopArchiver();
        if (run == null) {
            throw new IllegalStateException("o nó ainda não subiu");
        }
        ProcessBuilder builder = java(lib, "com.axonbase.core.dr.DrTool", List.of("watch",
            "--store", query.get("store"), "--epoch", query.get("epoch"), "--node", id,
            "--backups", run.resolve("db").resolve(".physical-backups").toString(),
            "--journal", run.resolve("db").resolve(".pitr-journal").toString(),
            "--every", query.getOrDefault("every", "5")));
        builder.redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(run.resolve("archive.log").toFile()));
        archiver = builder.start();
        return "E5 node=" + id + " archiving";
    }

    private String stopArchiver() throws InterruptedException {
        if (archiver != null) {
            archiver.destroy();
            archiver.waitFor(30, TimeUnit.SECONDS);
            archiver = null;
        }
        return "E5 node=" + id + " archiver stopped";
    }

    /** Restaura do arquivamento num diretório novo, usado pela próxima subida com {@code data=restored}. */
    private String restore(Map<String, String> query) throws Exception {
        Path fresh = newRun();
        ProcessBuilder builder = java(lib, "com.axonbase.core.dr.DrTool", List.of("restore",
            "--store", query.get("store"), "--epoch", query.get("epoch"),
            "--target", fresh.resolve("db").toString(), "--work", fresh.resolve("work").toString(),
            "--at", query.getOrDefault("at", "latest")));
        Path log = fresh.resolve("restore.log");
        builder.redirectErrorStream(true).redirectOutput(log.toFile());
        Process process = builder.start();
        if (!process.waitFor(30, TimeUnit.MINUTES)) {
            process.destroyForcibly();
            throw new IllegalStateException("a restauração passou de 30 minutos");
        }
        String output = Files.readString(log);
        if (process.exitValue() != 0) {
            throw new IllegalStateException("restauração falhou: " + output);
        }
        restored = fresh;
        return output;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                String[] parts = pair.split("=", 2);
                out.put(parts[0], URLDecoder.decode(parts.length > 1 ? parts[1] : "", StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static void reply(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    @Override
    public synchronized void close() {
        control.stop(0);
        try {
            stopArchiver();
            end(true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
