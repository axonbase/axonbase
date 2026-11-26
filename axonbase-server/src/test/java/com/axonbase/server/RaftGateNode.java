package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.cluster.ClockHooks;
import com.axonbase.core.cluster.ClusterConfig;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.cluster.LearnerBootstrap;
import com.axonbase.core.cluster.RaftOptions;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.Stream;

/**
 * Agente de um nó para os portões da E2 em máquinas separadas. Sobe uma vez em cada máquina e o controlador
 * ({@link RaftRemoteGates}) manda, por HTTP, subir e derrubar o nó (motor, consenso e servidor), gerar carga,
 * tirar backup e entrar como learner. Cada subida usa um diretório novo; nada é apagado.
 *
 * <p><strong>Só para uma rede isolada de benchmark:</strong> quem alcança a porta de controle e conhece o
 * token ({@code AXON_GATE_TOKEN}, obrigatório) lê e recria a base; o servidor HTTP sobe sem autenticação.</p>
 *
 * <p>Argumentos: {@code --id n1 --advertise <ip> --data <dir> [--offset 0]}. Portas: controle 8100, HTTP
 * (HyperText Transfer Protocol) 8000 e Raft 9000, somadas ao {@code offset}.</p>
 */
public final class RaftGateNode {

    static final int CONTROL_PORT = 8100;
    static final int HTTP_PORT = 8000;
    static final int RAFT_PORT = 9000;
    static final String TOKEN_HEADER = "Axon-Gate-Token";
    private static final String GROUP = "gate";
    private static final int LOAD_VALUE_BYTES = 4_096;
    private static final int LOAD_ROWS_PER_BATCH = 256;
    private static final int TRANSFERS = 4;

    private final String id;
    private final String advertise;
    private final Path data;
    private final int offset;
    private final String token;
    private int runs;
    private Path run;
    private RocksDbBackend backend;
    private ClusterRuntime cluster;
    private Datastore datastore;
    private AxonServer server;
    private Path backupDir;
    private volatile RocksDbBackend.SyncMode currentMode = RocksDbBackend.SyncMode.BATCH;
    private volatile Writer writer;

    private RaftGateNode(String id, String advertise, Path data, int offset, String token) {
        this.id = id;
        this.advertise = advertise;
        this.data = data;
        this.offset = offset;
        this.token = token;
    }

    public static void main(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.ROOT);
        Map<String, String> options = options(args, 0);
        String token = System.getenv("AXON_GATE_TOKEN");
        if (token == null || token.isBlank() || !options.containsKey("id") || !options.containsKey("advertise")
            || !options.containsKey("data")) {
            System.err.println("uso: AXON_GATE_TOKEN=... RaftGateNode --id n1 --advertise <ip> --data <dir> "
                + "[--offset 0]");
            System.exit(2);
        }
        int offset = Integer.parseInt(options.getOrDefault("offset", "0"));
        System.setOut(withoutServedRequests(System.out));
        RaftGateNode node = new RaftGateNode(options.get("id"), options.get("advertise"),
            Files.createDirectories(Path.of(options.get("data"))), offset, token);
        HttpServer control = HttpServer.create(new InetSocketAddress(CONTROL_PORT + offset), 64);
        control.createContext("/", node::serve);
        control.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        control.start();
        System.out.println("GATE agent id=" + node.id + " control=" + (CONTROL_PORT + offset));
        Thread.currentThread().join();
    }

    /**
     * A saída do nó sem a linha de cada requisição atendida com sucesso: sob carga era um gigabyte por nó, gravado
     * no meio da medida. Recusas, erros e os eventos do cluster continuam saindo.
     */
    private static PrintStream withoutServedRequests(OutputStream out) {
        return new PrintStream(out, true, StandardCharsets.UTF_8) {
            @Override
            public void println(String line) {
                if (line == null || !line.contains("\"event\":\"http_request\"") || !line.contains("\"status\":200")) {
                    super.println(line);
                }
            }
        };
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
            String path = exchange.getRequestURI().getPath();
            if (path.equals("/file")) {
                send(exchange, query.get("path"));
                return;
            }
            try {
                reply(exchange, 200, route(path, query));
            } catch (IllegalStateException refused) {
                reply(exchange, 409, String.valueOf(refused.getMessage()));
            } catch (Exception failure) {
                failure.printStackTrace();
                reply(exchange, 500, failure.toString());
            }
        }
    }

    private String route(String path, Map<String, String> query) throws Exception {
        return switch (path) {
            case "/start" -> start(query);
            case "/stop" -> stop();
            case "/status" -> status();
            case "/cpu" -> cpu();
            case "/load" -> load(Double.parseDouble(query.get("gb")));
            case "/write" -> measureWrites(Integer.parseInt(query.get("writers")),
                Integer.parseInt(query.get("seconds")), Integer.parseInt(query.getOrDefault("warmup", "5")),
                query.get("scenario"));
            case "/writer/start" -> startWriter(Integer.parseInt(query.get("rate")));
            case "/writer/report" -> requireWriter().report(query.getOrDefault("phase", "run"));
            case "/writer/stop" -> stopWriter();
            case "/backup" -> backup();
            case "/files" -> files();
            case "/join" -> join(query);
            case "/digest" -> digest(Long.parseLong(query.get("rows")), Integer.parseInt(query.get("count")));
            default -> throw new IllegalStateException("rota desconhecida: " + path);
        };
    }

    // ---- ciclo de vida ----

    private synchronized String start(Map<String, String> query) throws Exception {
        stop();
        run = Files.createDirectories(data.resolve("run-" + System.currentTimeMillis() + "-" + ++runs));
        boot(query, query.getOrDefault("role", "voter"));
        return "GATE node=" + id + " started storage=" + backendMode(query) + " raft_log=" + backendMode(query)
            + " dir=" + run;
    }

    private static RocksDbBackend.SyncMode backendMode(Map<String, String> query) {
        return RocksDbBackend.SyncMode.valueOf(query.getOrDefault("mode", "batch").toUpperCase(java.util.Locale.ROOT));
    }

    private void boot(Map<String, String> query, String role) throws Exception {
        RocksDbBackend.SyncMode mode = backendMode(query);
        currentMode = mode;
        backend = new RocksDbBackend(run.resolve("db").toString(), mode);
        datastore = new Datastore(backend);
        datastore.createDatabase("app", "main");
        if (!role.equals("solo")) {
            List<InetSocketAddress> peers = Arrays.stream(query.getOrDefault("peers", "").split(","))
                .filter(p -> !p.isBlank()).map(RaftGateNode::address).toList();
            List<String> learners = Arrays.stream(query.getOrDefault("learners", "").split(","))
                .filter(p -> !p.isBlank()).toList();
            ClusterConfig config = new ClusterConfig(id, GROUP,
                new InetSocketAddress(advertise, RAFT_PORT + offset), peers, role.equals("learner"));
            cluster = new ClusterRuntime(config, run.resolve("raft"), backend,
                RaftOptions.defaults().withSync(mode).withAllowedLearners(learners));
            // como o servidor de verdade: os portões medem a replicação com o carimbo de tempo ligado
            cluster.hooks(new ClockHooks(backend.clock()));
            cluster.appliedBatchListener(datastore.appliedBatchListener());
            datastore.commitCoordinator(cluster, id);
        }
        ServerConfig serverConfig = ServerConfig.load(null, Map.of("AXON_BIND", "0.0.0.0",
            "AXON_PORT", Integer.toString(HTTP_PORT + offset), "AXON_REQUIRE_AUTH", "false"));
        server = AxonServer.start(datastore, java.util.UUID.randomUUID().toString(), serverConfig);
        if (cluster != null) {
            server.clusterStatus(cluster);
        }
    }

    private static InetSocketAddress address(String raw) {
        int split = raw.lastIndexOf(':');
        return new InetSocketAddress(raw.substring(0, split), Integer.parseInt(raw.substring(split + 1)));
    }

    private synchronized String stop() throws Exception {
        stopWriter();
        if (server != null) {
            server.stop();
            server = null;
        }
        if (cluster != null) {
            cluster.close();
            cluster = null;
        }
        if (backend != null) {
            backend.close();
            backend = null;
        }
        datastore = null;
        backupDir = null;
        return "GATE node=" + id + " stopped";
    }

    private synchronized String status() {
        if (backend == null) {
            return "role=STOPPED ready=false commit=0 last=0 elections=0";
        }
        if (cluster == null) {
            return "role=SOLO ready=true commit=0 last=0 elections=0";
        }
        return "role=" + cluster.role() + " ready=" + cluster.status().ready() + " commit=" + cluster.commitIndex()
            + " last=" + cluster.lastIndex() + " snapshot_bytes=" + cluster.snapshotBytes()
            + " need_backup=" + cluster.learnersNeedingBackup().size() + " elections=" + cluster.electionsStarted();
    }

    /** Fração do processador da máquina usada desde a chamada anterior. */
    private String cpu() {
        var machine = (com.sun.management.OperatingSystemMXBean)
            java.lang.management.ManagementFactory.getOperatingSystemMXBean();
        return String.format("GATE cpu node=%s cpu=%.2f", id, machine.getCpuLoad());
    }

    private synchronized ClusterRuntime leader() {
        if (cluster == null || cluster.role() != ClusterRole.LEADER) {
            throw new IllegalStateException("este nó não é o líder");
        }
        return cluster;
    }

    // ---- carga ----

    /**
     * Grava a base neste líder. Se uma escrita falha (o líder mudou, por exemplo), para e responde
     * {@code complete=false}; o controlador repete no líder da vez, que pula os lotes que já tem.
     */
    private String load(double gigabytes) throws Exception {
        ClusterRuntime leader = leader();
        RocksDbBackend store = backend;
        long rows = (long) (gigabytes * (1L << 30) / LOAD_VALUE_BYTES);
        long started = System.nanoTime();
        AtomicLong next = new AtomicLong();
        AtomicLong written = new AtomicLong();
        AtomicLong skipped = new AtomicLong();
        AtomicReference<String> stopped = new AtomicReference<>();
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> tasks = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            tasks.add(pool.submit(() -> {
                for (long first = next.getAndAdd(LOAD_ROWS_PER_BATCH); first < rows && stopped.get() == null;
                     first = next.getAndAdd(LOAD_ROWS_PER_BATCH)) {
                    long end = Math.min(rows, first + LOAD_ROWS_PER_BATCH);
                    // O lote entra inteiro: com a última linha aqui, uma tentativa anterior já o gravou.
                    if (store.get("base-" + (end - 1)).isPresent()) {
                        skipped.addAndGet(end - first);
                        continue;
                    }
                    SplittableRandom random = new SplittableRandom(first);
                    Map<String, byte[]> puts = new LinkedHashMap<>();
                    for (long row = first; row < end; row++) {
                        byte[] value = new byte[LOAD_VALUE_BYTES];
                        random.nextBytes(value);
                        puts.put("base-" + row, value);
                    }
                    try {
                        leader.confirm(id, new CommittedBatch("load-" + first + "-" + started, puts, Set.of()));
                        written.addAndGet(end - first);
                    } catch (RuntimeException notConfirmed) {
                        if (stopped.compareAndSet(null, notConfirmed.toString())) {
                            notConfirmed.printStackTrace();
                        }
                    }
                }
            }));
        }
        try {
            for (Future<?> task : tasks) {
                task.get();
            }
        } finally {
            pool.shutdownNow();
        }
        String error = stopped.get();
        return String.format("GATE3 load node=%s gigabytes=%.2f rows=%d written=%d skipped=%d seconds=%.0f complete=%b%s",
            id, gigabytes, rows, written.get(), skipped.get(), seconds(started), error == null,
            error == null ? "" : " error=" + error);
    }

    private String measureWrites(int writers, int seconds, int warmup, String scenario) throws Exception {
        ClusterRuntime leader = leader();
        Datastore ds = datastore;
        if (scenario.equals("sql") && ds.findDatabase("app", "main").catalog().table("pedido") == null) {
            ds.execute("DEFINE TABLE pedido", session(), null);
        }
        byte[] value = new byte[256];
        String text = "x".repeat(200);
        String tag = scenario + writers + "-" + System.nanoTime();
        AtomicBoolean measuring = new AtomicBoolean();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong failed = new AtomicLong();
        List<List<Long>> latencies = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            int worker = w;
            List<Long> mine = new ArrayList<>(1 << 14);
            latencies.add(mine);
            threads.add(Thread.ofPlatform().start(() -> {
                for (long i = 0; running.get(); i++) {
                    long started = System.nanoTime();
                    try {
                        if (scenario.equals("sql")) {
                            ds.execute("CREATE pedido:`" + tag + "-" + worker + "-" + i + "` CONTENT { loja: "
                                + worker + ", nota: '" + text + "' }", session(), null);
                        } else {
                            leader.confirm(id, new CommittedBatch(tag + "-" + worker + "-" + i,
                                Map.of(tag + "-" + worker + "-" + i, value), Set.of()));
                        }
                    } catch (RuntimeException notConfirmed) {
                        if (failed.incrementAndGet() == 1) {
                            notConfirmed.printStackTrace();
                        }
                        LockSupport.parkNanos(10_000_000);
                        continue;
                    }
                    if (measuring.get()) {
                        mine.add(System.nanoTime() - started);
                    }
                }
            }));
        }
        Thread.sleep(warmup * 1_000L);
        long from = System.nanoTime();
        measuring.set(true);
        Thread.sleep(seconds * 1_000L);
        measuring.set(false);
        double elapsed = seconds(from);
        running.set(false);
        for (Thread thread : threads) {
            thread.join(60_000);
        }
        long[] all = latencies.stream().flatMap(List::stream).mapToLong(Long::longValue).sorted().toArray();
        return String.format(
            "GATE1 mode=%s storage=%s raft_log=%s scenario=%s writers=%d ops_s=%.0f p50_ms=%.2f p99_ms=%.2f failed=%d",
            currentMode, currentMode, currentMode, scenario, writers,
            all.length / elapsed, percentile(all, 0.50), percentile(all, 0.99), failed.get());
    }

    private static Session session() {
        Session session = Session.create();
        session.namespace("app");
        session.database("main");
        return session;
    }

    // ---- escritor de fundo (portão 3) ----

    /** Escreve em ritmo fixo e anota latência e a maior pausa entre confirmações. */
    private final class Writer {
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<Long> latencies = new ArrayList<>();
        private final Thread thread;
        private long last = System.nanoTime();
        private long since = System.nanoTime();
        private long maxGap;
        private long failed;

        Writer(ClusterRuntime leader, int rate) {
            thread = Thread.ofPlatform().start(() -> {
                byte[] value = new byte[1_024];
                long interval = 1_000_000_000L / rate;
                long next = System.nanoTime();
                for (long i = 0; running.get(); i++) {
                    long wait = next - System.nanoTime();
                    if (wait > 0) {
                        LockSupport.parkNanos(wait);
                    }
                    next = Math.max(next + interval, System.nanoTime() - interval);
                    long started = System.nanoTime();
                    try {
                        leader.confirm(id, new CommittedBatch("w" + i + "-" + started,
                            Map.of("w" + started, value), Set.of()));
                        confirmed(started);
                    } catch (RuntimeException notConfirmed) {
                        synchronized (this) {
                            failed++;
                        }
                    }
                }
            });
        }

        private synchronized void confirmed(long started) {
            long now = System.nanoTime();
            maxGap = Math.max(maxGap, now - last);
            last = now;
            latencies.add(now - started);
        }

        /** Resumo desde o relatório anterior, que é zerado. */
        synchronized String report(String phase) {
            long now = System.nanoTime();
            double elapsed = (now - since) / 1e9;
            long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
            String line = String.format("GATE3 writer phase=%s seconds=%.1f writes_s=%.0f p99_ms=%.1f "
                    + "max_write_ms=%.0f max_gap_ms=%.0f failed=%d", phase, elapsed, sorted.length / elapsed,
                percentile(sorted, 0.99), sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1e6,
                Math.max(maxGap, now - last) / 1e6, failed);
            latencies.clear();
            maxGap = 0;
            failed = 0;
            since = now;
            last = now;
            return line;
        }

        void stop() throws InterruptedException {
            running.set(false);
            thread.join(30_000);
        }
    }

    private synchronized String startWriter(int rate) throws InterruptedException {
        stopWriter();
        writer = new Writer(leader(), rate);
        return "GATE3 writer started rate=" + rate;
    }

    private Writer requireWriter() {
        Writer current = writer;
        if (current == null) {
            throw new IllegalStateException("nenhum escritor ativo");
        }
        return current;
    }

    private String stopWriter() throws InterruptedException {
        Writer current = writer;
        if (current != null) {
            current.stop();
            writer = null;
        }
        return "GATE3 writer stopped";
    }

    // ---- backup e entrada de um nó novo ----

    private synchronized String backup() throws IOException {
        if (backend == null) {
            throw new IllegalStateException("nó parado");
        }
        long started = System.nanoTime();
        backupDir = run.resolve("backup-" + started);
        try (PhysicalBackups backups = PhysicalBackups.open(backupDir)) {
            backups.create(backend);
        }
        return String.format("GATE3 backup node=%s seconds=%.1f", id, seconds(started));
    }

    /** Uma linha {@code <bytes> <caminho>} por arquivo do último backup. */
    private String files() throws IOException {
        Path root = backupDir;
        if (root == null) {
            throw new IllegalStateException("nenhum backup neste nó");
        }
        StringBuilder out = new StringBuilder();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                out.append(Files.size(file)).append(' ').append(root.relativize(file)).append('\n');
            }
        }
        return out.toString();
    }

    private void send(HttpExchange exchange, String relative) throws IOException {
        Path root = backupDir;
        Path file = root == null || relative == null ? null : root.resolve(relative).normalize();
        if (file == null || !file.startsWith(root) || !Files.isRegularFile(file)) {
            reply(exchange, 404, "arquivo inexistente");
            return;
        }
        exchange.sendResponseHeaders(200, Files.size(file));
        try (OutputStream out = exchange.getResponseBody()) {
            Files.copy(file, out);
        }
    }

    /** Baixa o backup do nó de origem, restaura e sobe como learner. */
    private synchronized String join(Map<String, String> query) throws Exception {
        stop();
        run = Files.createDirectories(data.resolve("run-" + System.currentTimeMillis() + "-" + ++runs));
        String source = query.get("from");
        Path downloaded = Files.createDirectories(run.resolve("download"));
        long started = System.nanoTime();
        HttpClient http = HttpClient.newHttpClient();
        String listing = http.send(request(source + "/files"), HttpResponse.BodyHandlers.ofString()).body();
        ExecutorService pool = Executors.newFixedThreadPool(TRANSFERS);
        List<Future<Long>> transfers = new ArrayList<>();
        try {
            for (String line : listing.split("\n")) {
                String[] part = line.split(" ", 2);
                Path target = downloaded.resolve(part[1]).normalize();
                if (!target.startsWith(downloaded)) {
                    throw new IllegalStateException("caminho fora do backup: " + part[1]);
                }
                Files.createDirectories(target.getParent());
                transfers.add(pool.submit(() -> {
                    HttpResponse<InputStream> response = http.send(
                        request(source + "/file?path=" + URLEncoder.encode(part[1], StandardCharsets.UTF_8)),
                        HttpResponse.BodyHandlers.ofInputStream());
                    try (InputStream body = response.body()) {
                        long copied = Files.copy(body, target);
                        if (response.statusCode() != 200 || copied != Long.parseLong(part[0])) {
                            throw new IOException("transferência incompleta de " + part[1]);
                        }
                        return copied;
                    }
                }));
            }
            long bytes = 0;
            for (Future<Long> transfer : transfers) {
                bytes += transfer.get();
            }
            double transferred = seconds(started);
            long restoring = System.nanoTime();
            long index = LearnerBootstrap.restore(downloaded, run.resolve("db")).orElseThrow().index();
            double restored = seconds(restoring);
            boot(query, "learner");
            return String.format("GATE3 join node=%s bytes=%d transfer_seconds=%.1f restore_seconds=%.1f index=%d",
                id, bytes, transferred, restored, index);
        } finally {
            pool.shutdownNow();
        }
    }

    private HttpRequest request(String url) {
        return HttpRequest.newBuilder(URI.create(url)).header(TOKEN_HEADER, token).build();
    }

    /** Resumo de uma amostra das linhas carregadas, para comparar dois nós. */
    private synchronized String digest(long rows, int count) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        SplittableRandom random = new SplittableRandom(7);
        int missing = 0;
        for (int i = 0; i < count; i++) {
            byte[] value = backend.get("base-" + random.nextLong(rows)).orElse(null);
            if (value == null) {
                missing++;
            } else {
                digest.update(value);
            }
        }
        return "GATE3 digest node=" + id + " sampled=" + count + " missing=" + missing + " sha256="
            + HexFormat.of().formatHex(digest.digest());
    }

    // ---- utilidades ----

    private static double percentile(long[] sorted, double p) {
        return sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, (long) (sorted.length * p))] / 1e6;
    }

    private static double seconds(long since) {
        return (System.nanoTime() - since) / 1e9;
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int split = pair.indexOf('=');
                if (split > 0) {
                    out.put(pair.substring(0, split),
                        URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
                }
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
}
