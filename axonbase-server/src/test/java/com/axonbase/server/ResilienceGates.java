package com.axonbase.server;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Controlador dos portões da E5, na máquina geradora de carga. Fala com um {@link ResilienceNode} em cada nó e com o
 * servidor de verdade que ele sobe.
 *
 * <pre>
 * ResilienceGates failover --nodes ip1,ip2,ip3 [--writers 32] [--rounds 5] [--between 15]
 * ResilienceGates upgrade  --nodes ip1,ip2,ip3 [--writers 16] [--settle 15]
 * ResilienceGates qos      --nodes ip1,ip2,ip3 [--rows 200000] [--seconds 120] [--writers 32] [--analysts 8]
 *                          [--qos auto]
 * ResilienceGates dr       --nodes ip1,ip2,ip3 --store s3 [--gb 10] [--writers 8] [--seconds 60] [--epoch e5]
 * </pre>
 *
 * <p>Um endereço pode levar um deslocamento de portas ({@code 127.0.0.1+10}), para ensaiar tudo numa máquina. Cada
 * linha de resultado começa com {@code E5}; o veredito é {@code E5 <portão> RESULT PASS|FAIL} e o processo sai com 1
 * quando o portão reprova.</p>
 */
public final class ResilienceGates {

    static final long MAXIMUM_ELECTION_MILLIS = 10_000;
    static final double MAXIMUM_P99_RATIO = 1.20;
    static final long MAXIMUM_RTO_MILLIS = 10 * 60 * 1_000;
    /** Folga entre o relógio do gerador e o do líder na conferência do PITR. */
    private static final long CLOCK_MARGIN_MILLIS = 2_000;
    private static final int ROW_BYTES = 4_096;
    private static final int ROWS_PER_SCRIPT = 64;

    private final String token;
    private final Map<String, String> options;
    private final PrintStream out;
    private final HttpClient agents = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    /** Um nó: o agente e o servidor. */
    record Node(String host, int offset) {
        static Node parse(String spec) {
            int plus = spec.indexOf('+');
            return plus < 0 ? new Node(spec.trim(), 0)
                : new Node(spec.substring(0, plus).trim(), Integer.parseInt(spec.substring(plus + 1).trim()));
        }

        String control() {
            return "http://" + host + ":" + (ResilienceNode.CONTROL_PORT + offset);
        }

        String http() {
            return "http://" + host + ":" + (ResilienceNode.HTTP_PORT + offset);
        }

        String raft() {
            return host + ":" + (ResilienceNode.RAFT_PORT + offset);
        }
    }

    /** O que um escritor confirmou, com os instantes do próprio gerador. */
    record Write(String key, long sentMillis, long ackedMillis) {
    }

    ResilienceGates(String token, Map<String, String> options, PrintStream out) {
        this.token = token;
        this.options = options;
        this.out = out;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        String token = System.getenv("AXON_GATE_TOKEN");
        Map<String, String> options = ResilienceNode.options(args, 1);
        if (args.length == 0 || token == null || token.isBlank() || !options.containsKey("nodes")) {
            System.err.println("uso: AXON_GATE_TOKEN=... ResilienceGates failover|upgrade|qos|dr --nodes ip1,ip2,ip3");
            System.exit(2);
        }
        System.exit(new ResilienceGates(token, options, System.out).run(args[0]) ? 0 : 1);
    }

    /** @return se o portão passou */
    boolean run(String gate) throws Exception {
        List<Node> nodes = Arrays.stream(options.get("nodes").split(",")).map(Node::parse).toList();
        try (GateClient client = new GateClient(nodes.stream().map(Node::http).toList())) {
            boolean passed = switch (gate) {
                case "failover" -> failover(nodes, client);
                case "upgrade" -> upgrade(nodes, client);
                case "qos" -> qos(nodes, client);
                case "dr" -> disasterRecovery(nodes, client);
                default -> throw new IllegalArgumentException("portão desconhecido: " + gate);
            };
            out.println("E5 " + gate + " RESULT " + (passed ? "PASS" : "FAIL"));
            return passed;
        } catch (RuntimeException | IOException failure) {
            out.println("E5 " + gate + " error=" + failure.getMessage());
            out.println("E5 " + gate + " RESULT FAIL");
            return false;
        } finally {
            for (Node node : nodes) {
                quietly(node, "/archive/stop");
                quietly(node, "/stop");
            }
        }
    }

    private int option(String name, int fallback) {
        return Integer.parseInt(options.getOrDefault(name, Integer.toString(fallback)));
    }

    // ---- cluster ----

    private String agent(Node node, String path) throws IOException, InterruptedException {
        HttpResponse<String> response = agents.send(HttpRequest.newBuilder(URI.create(node.control() + path))
            .header(ResilienceNode.TOKEN_HEADER, token).timeout(Duration.ofMinutes(35))
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException("agente " + node.host() + " " + path + ": " + response.body());
        }
        return response.body();
    }

    private void quietly(Node node, String path) {
        try {
            agent(node, path);
        } catch (IOException | RuntimeException ignored) {
            // limpeza: o agente pode já ter caído
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String startPath(List<Node> nodes, Node node, String extra) throws IOException, InterruptedException {
        StringBuilder peers = new StringBuilder();
        StringBuilder urls = new StringBuilder();
        for (Node other : nodes) {
            String info = agent(other, "/info");
            String id = info.substring(3, info.indexOf(' '));
            urls.append(id).append('=').append(other.http()).append(',');
            if (!other.equals(node)) {
                peers.append(other.raft()).append(',');
            }
        }
        return "/start?peers=" + encode(peers.toString()) + "&AXON_CLUSTER_HTTP_PEERS=" + encode(urls.toString())
            + extra;
    }

    private void startCluster(List<Node> nodes, GateClient client, String extra)
            throws IOException, InterruptedException {
        for (Node node : nodes) {
            agent(node, "/stop");
        }
        for (Node node : nodes) {
            agent(node, startPath(nodes, node, extra));
        }
        for (Node node : nodes) {
            await("o nó " + node.host() + " não ficou pronto", 120, () -> client.isReady(node.http()));
        }
    }

    private interface Condition {
        boolean met() throws IOException, InterruptedException;
    }

    private static void await(String message, int seconds, Condition condition)
            throws IOException, InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (!condition.met()) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException(message);
            }
            Thread.sleep(50);
        }
    }

    private static Map<String, AxonValue> object(HttpResponse<String> response) {
        return AxonJson.parseDocument(response.body()).asObject();
    }

    private Node leader(List<Node> nodes, GateClient client) throws IOException, InterruptedException {
        Node[] found = new Node[1];
        await("nenhum líder", 60, () -> {
            for (Node node : nodes) {
                try {
                    if ("LEADER".equals(object(client.get(node.http(), "/status")).get("role").asString())) {
                        found[0] = node;
                        return true;
                    }
                } catch (IOException down) {
                    // nó parado
                }
            }
            return false;
        });
        return found[0];
    }

    private long commitIndex(Node node, GateClient client) throws IOException, InterruptedException {
        return object(client.get(node.http(), "/status")).get("commit_index").asLong();
    }

    private void awaitCaughtUp(List<Node> nodes, Node node, GateClient client)
            throws IOException, InterruptedException {
        long target = commitIndex(leader(nodes, client), client);
        await("o nó " + node.host() + " não alcançou o líder", 600,
            () -> client.isReady(node.http()) && commitIndex(node, client) >= target);
    }

    private static void require(GateClient.Reply reply) {
        if (reply.verdict() != GateClient.Verdict.DONE) {
            throw new IllegalStateException("comando falhou: " + reply.status() + " " + reply.body());
        }
    }

    // ---- carga de escrita ----

    /** Escritores contínuos: cada chave confirmada é guardada, e cada falha visível também. */
    private static final class Writers implements AutoCloseable {
        final Queue<Write> confirmed = new ConcurrentLinkedQueue<>();
        final Queue<String> visible = new ConcurrentLinkedQueue<>();
        final AtomicLong unknown = new AtomicLong();
        private final AtomicBoolean running = new AtomicBoolean(true);
        private final List<Thread> threads = new ArrayList<>();

        Writers(GateClient client, int count) {
            String run = Long.toString(System.nanoTime(), 36);
            for (int w = 0; w < count; w++) {
                int worker = w;
                threads.add(Thread.ofPlatform().start(() -> {
                    for (long i = 0; running.get(); i++) {
                        String key = run + "-" + worker + "-" + i;
                        long sent = System.currentTimeMillis();
                        GateClient.Reply reply =
                            client.sql("CREATE item:`" + key + "` CONTENT { k: '" + key + "' }");
                        if (reply.verdict() == GateClient.Verdict.DONE) {
                            confirmed.add(new Write(key, sent, System.currentTimeMillis()));
                        } else {
                            if (reply.verdict() == GateClient.Verdict.UNKNOWN) {
                                unknown.incrementAndGet();
                            }
                            visible.add(reply.status() + " " + reply.body());
                            GateClient.pause(10);
                        }
                    }
                }));
            }
        }

        @Override
        public void close() throws InterruptedException {
            running.set(false);
            for (Thread thread : threads) {
                thread.join(120_000);
            }
        }
    }

    /** As chaves presentes num nó, lidas de forma linearizável. */
    private Set<String> keys(Node node, GateClient client) throws IOException, InterruptedException {
        GateClient.Reply reply = new GateClient.Reply(GateClient.Verdict.RETRY, 0, "");
        for (int attempt = 0; attempt < 600 && reply.verdict() != GateClient.Verdict.DONE; attempt++) {
            reply = client.send(node.http(), "SELECT VALUE k FROM item",
                Map.of("Axon-Read-Consistency", "linearizable"));
            if (reply.verdict() != GateClient.Verdict.DONE) {
                Thread.sleep(100);
            }
        }
        require(reply);
        Set<String> keys = new HashSet<>();
        for (AxonValue value : reply.json().asArray()) {
            if (value.isString()) {
                keys.add(value.asString());
            }
        }
        return keys;
    }

    private long lost(List<Node> nodes, GateClient client, Queue<Write> confirmed, String gate)
            throws IOException, InterruptedException {
        long total = 0;
        for (Node node : nodes) {
            awaitCaughtUp(nodes, node, client);
            Set<String> present = keys(node, client);
            long missing = confirmed.stream().filter(w -> !present.contains(w.key())).count();
            out.println("E5 " + gate + " node=" + node.host() + "+" + node.offset() + " rows=" + present.size()
                + " acknowledged=" + confirmed.size() + " lost=" + missing);
            total += missing;
        }
        return total;
    }

    // ---- portão: líder morto sob carga ----

    private boolean failover(List<Node> nodes, GateClient client) throws Exception {
        startCluster(nodes, client, "&data=new");
        require(client.sql("DEFINE TABLE item"));
        int rounds = option("rounds", 5);
        long between = option("between", 15) * 1_000L;
        long worst = 0;
        Writers writers = new Writers(client, option("writers", 32));
        try {
            for (int round = 1; round <= rounds; round++) {
                Thread.sleep(between);
                Node leader = leader(nodes, client);
                long killed = System.currentTimeMillis();
                agent(leader, "/kill");
                // a primeira escrita enviada depois da morte e confirmada prova que há um líder novo
                long[] first = {0};
                await("nenhuma escrita confirmada depois de matar o líder", 120, () -> {
                    first[0] = writers.confirmed.stream().filter(w -> w.sentMillis() > killed)
                        .mapToLong(Write::ackedMillis).min().orElse(0);
                    return first[0] > 0;
                });
                long millis = first[0] - killed;
                worst = Math.max(worst, millis);
                out.println("E5 failover round=" + round + " killed=" + leader.host() + "+" + leader.offset()
                    + " election_ms=" + millis);
                agent(leader, startPath(nodes, leader, "&data=keep"));
                awaitCaughtUp(nodes, leader, client);
            }
        } finally {
            writers.close();
        }
        long lost = lost(nodes, client, writers.confirmed, "failover");
        // com o líder morto, as escritas em voo ficam sem desfecho: contam, mas não reprovam
        out.println("E5 failover worst_election_ms=" + worst + " lost=" + lost + " unknown_outcomes="
            + writers.unknown.get() + " retries=" + client.retries());
        return worst < MAXIMUM_ELECTION_MILLIS && lost == 0 && !writers.confirmed.isEmpty();
    }

    // ---- portão: atualização um nó de cada vez ----

    private boolean upgrade(List<Node> nodes, GateClient client) throws Exception {
        startCluster(nodes, client, "&data=new&build=old&AXON_DRAIN_GRACE_MS=2000");
        require(client.sql("DEFINE TABLE item"));
        long settle = option("settle", 15) * 1_000L;
        Writers writers = new Writers(client, option("writers", 16));
        try {
            Thread.sleep(settle);
            Node last = leader(nodes, client);
            List<Node> order = new ArrayList<>(nodes.stream().filter(n -> !n.equals(last)).toList());
            order.add(last);
            for (Node node : order) {
                long started = System.currentTimeMillis();
                HttpResponse<String> drain = client.post(node.http(), "/admin/v1/cluster/drain");
                if (drain.statusCode() == 202) {
                    await("a drenagem de " + node.host() + " não terminou", 120, () -> !"DRAINING".equals(
                        object(client.get(node.http(), "/admin/v1/cluster/drain")).get("state").asString()));
                    String state = object(client.get(node.http(), "/admin/v1/cluster/drain")).get("state").asString();
                    out.println("E5 upgrade node=" + node.host() + "+" + node.offset() + " drain=" + state);
                } else {
                    out.println("E5 upgrade node=" + node.host() + "+" + node.offset()
                        + " drain=UNSUPPORTED status=" + drain.statusCode());
                }
                agent(node, "/stop");
                agent(node, startPath(nodes, node, "&data=keep&build=new&AXON_DRAIN_GRACE_MS=2000"));
                awaitCaughtUp(nodes, node, client);
                String version = client.get(node.http(), "/version").body();
                out.println("E5 upgrade node=" + node.host() + "+" + node.offset() + " version=" + version
                    + " seconds=" + (System.currentTimeMillis() - started) / 1000);
                Thread.sleep(settle);
            }
        } finally {
            writers.close();
        }
        long lost = lost(nodes, client, writers.confirmed, "upgrade");
        writers.visible.stream().limit(10).forEach(error -> out.println("E5 upgrade sample " + error));
        out.println("E5 upgrade visible_errors=" + writers.visible.size() + " unknown_outcomes="
            + writers.unknown.get() + " lost=" + lost + " retries=" + client.retries());
        return writers.visible.isEmpty() && lost == 0 && !writers.confirmed.isEmpty();
    }

    // ---- portão: análises sem piorar as transações ----

    private record Measure(double p99Millis, double operationsPerSecond, long failed, long analytical,
                           long rejected) {
    }

    private boolean qos(List<Node> nodes, GateClient client) throws Exception {
        String mode = options.getOrDefault("qos", "auto");
        startCluster(nodes, client, "&data=new&AXON_QOS=" + encode(mode));
        require(client.sql("DEFINE TABLE item"));
        int rows = option("rows", 200_000);
        load(client, rows, 100);
        int seconds = option("seconds", 120);
        int writers = option("writers", 32);
        Thread.sleep(10_000);
        Measure alone = measure(nodes, client, rows, writers, 0, seconds);
        Measure mixed = measure(nodes, client, rows, writers, option("analysts", 8), seconds);
        double ratio = alone.p99Millis() == 0 ? Double.POSITIVE_INFINITY : mixed.p99Millis() / alone.p99Millis();
        for (var phase : Map.of("alone", alone, "mixed", mixed).entrySet()) {
            Measure m = phase.getValue();
            out.println(String.format("E5 qos mode=%s phase=%s p99_ms=%.2f ops_s=%.0f failed=%d analytical_done=%d "
                + "analytical_rejected=%d", mode, phase.getKey(), m.p99Millis(), m.operationsPerSecond(), m.failed(),
                m.analytical(), m.rejected()));
        }
        out.println(String.format("E5 qos mode=%s p99_ratio=%.3f limit=%.2f", mode, ratio, MAXIMUM_P99_RATIO));
        // sem análise concluída o portão não provou nada
        return ratio <= MAXIMUM_P99_RATIO && mixed.analytical() > 0 && alone.failed() == 0 && mixed.failed() == 0;
    }

    private void load(GateClient client, int rows, int groups) {
        for (int first = 0; first < rows; first += 500) {
            StringBuilder script = new StringBuilder();
            for (int row = first; row < Math.min(rows, first + 500); row++) {
                script.append("CREATE item:").append(row).append(" CONTENT { k: 'base-").append(row)
                    .append("', g: ").append(row % groups).append(", v: 0 };");
            }
            require(client.sql(script.toString()));
        }
        out.println("E5 qos loaded rows=" + rows);
    }

    /** Transações (leitura por id e atualização) no líder, com ou sem analistas ao lado. */
    private Measure measure(List<Node> nodes, GateClient client, int rows, int writers, int analysts, int seconds)
            throws Exception {
        String leader = leader(nodes, client).http();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong failed = new AtomicLong();
        AtomicLong analytical = new AtomicLong();
        AtomicLong rejected = new AtomicLong();
        List<List<Long>> latencies = new ArrayList<>();
        List<Thread> threads = new ArrayList<>();
        for (int w = 0; w < writers; w++) {
            List<Long> mine = new ArrayList<>(1 << 16);
            latencies.add(mine);
            threads.add(Thread.ofPlatform().start(() -> {
                while (running.get()) {
                    int row = ThreadLocalRandom.current().nextInt(rows);
                    String sql = ThreadLocalRandom.current().nextBoolean() ? "SELECT * FROM item:" + row
                        : "UPDATE item:" + row + " SET v = v + 1";
                    long started = System.nanoTime();
                    GateClient.Reply reply = client.send(leader, sql, Map.of());
                    if (reply.verdict() == GateClient.Verdict.DONE) {
                        mine.add(System.nanoTime() - started);
                    } else if (!reply.body().contains("conflit")) {
                        failed.incrementAndGet();
                        GateClient.pause(10);
                    }
                }
            }));
        }
        for (int a = 0; a < analysts; a++) {
            int analyst = a;
            threads.add(Thread.ofPlatform().start(() -> {
                while (running.get()) {
                    String sql = analyst % 2 == 0 ? "SELECT g, count() FROM item GROUP BY g"
                        : "SELECT * FROM item WHERE g = " + ThreadLocalRandom.current().nextInt(100);
                    GateClient.Reply reply = client.send(leader, sql, Map.of());
                    if (reply.verdict() == GateClient.Verdict.DONE) {
                        analytical.incrementAndGet();
                    } else {
                        rejected.incrementAndGet();
                        GateClient.pause(200);
                    }
                }
            }));
        }
        long from = System.nanoTime();
        Thread.sleep(seconds * 1_000L);
        running.set(false);
        double elapsed = (System.nanoTime() - from) / 1e9;
        for (Thread thread : threads) {
            thread.join(120_000);
        }
        long[] all = latencies.stream().flatMap(List::stream).mapToLong(Long::longValue).sorted().toArray();
        return new Measure(percentile(all, 0.99), all.length / elapsed, failed.get(), analytical.get(),
            rejected.get());
    }

    static double percentile(long[] sorted, double fraction) {
        if (sorted.length == 0) {
            return 0;
        }
        int index = (int) Math.ceil(fraction * sorted.length) - 1;
        return sorted[Math.max(0, Math.min(sorted.length - 1, index))] / 1e6;
    }

    // ---- portão: recuperação de desastre ----

    private boolean disasterRecovery(List<Node> nodes, GateClient client) throws Exception {
        String store = options.getOrDefault("store", "s3");
        String epoch = options.getOrDefault("epoch", "e5-" + System.currentTimeMillis());
        double gigabytes = Double.parseDouble(options.getOrDefault("gb", "10"));
        int seconds = option("seconds", 60);
        String settings = "&AXON_PITR=on&AXON_PITR_SEGMENT_SECONDS=5";
        startCluster(nodes, client, "&data=new" + settings);
        require(client.sql("DEFINE TABLE item"));
        require(client.sql("DEFINE TABLE bulk"));
        bulk(client, (long) (gigabytes * (1L << 30) / ROW_BYTES));
        for (Node node : nodes) {
            agent(node, "/archive/start?store=" + encode(store) + "&epoch=" + encode(epoch));
        }
        Node leader = leader(nodes, client);
        Node source = nodes.stream().filter(n -> !n.equals(leader)).findFirst().orElseThrow();
        long backupStarted = System.currentTimeMillis();
        HttpResponse<String> backup = client.post(source.http(), "/admin/v1/backups/physical");
        if (backup.statusCode() != 200) {
            throw new IllegalStateException("backup falhou: " + backup.body());
        }
        out.println("E5 dr backup seconds=" + (System.currentTimeMillis() - backupStarted) / 1000 + " "
            + backup.body());
        Writers writers = new Writers(client, option("writers", 8));
        long instant;
        try {
            Thread.sleep(seconds * 500L);
            instant = System.currentTimeMillis();
            Thread.sleep(seconds * 500L);
        } finally {
            writers.close();
        }
        // fecha o segmento de cada nó e dá tempo ao arquivamento (a cada 5 s)
        for (Node node : nodes) {
            client.post(node.http(), "/admin/v1/backups/journal/seal");
        }
        long archiveWait = option("archive-wait", 60) * 1_000L;
        Thread.sleep(archiveWait);
        // perda total: os três caem de uma vez e nenhuma base antiga é usada de novo
        for (Node node : nodes) {
            agent(node, "/kill");
            agent(node, "/archive/stop");
        }
        long lostAt = System.currentTimeMillis();
        boolean past = restoreAndCheck(nodes, client, store, epoch, Instant.ofEpochMilli(instant).toString(),
            settings, writers.confirmed, instant, "pitr");
        boolean latest = restoreAndCheck(nodes, client, store, epoch, "latest", settings, writers.confirmed,
            Long.MAX_VALUE, "latest");
        out.println("E5 dr total_seconds_since_loss=" + (System.currentTimeMillis() - lostAt) / 1000
            + " gigabytes=" + gigabytes + " epoch=" + epoch);
        return past && latest;
    }

    private void bulk(GateClient client, long rows) {
        String filler = "x".repeat(ROW_BYTES - 64);
        AtomicLong next = new AtomicLong();
        List<Thread> threads = new ArrayList<>();
        Queue<String> failures = new ConcurrentLinkedQueue<>();
        long started = System.nanoTime();
        for (int t = 0; t < 8; t++) {
            threads.add(Thread.ofPlatform().start(() -> {
                for (long first = next.getAndAdd(ROWS_PER_SCRIPT); first < rows && failures.isEmpty();
                     first = next.getAndAdd(ROWS_PER_SCRIPT)) {
                    StringBuilder script = new StringBuilder();
                    for (long row = first; row < Math.min(rows, first + ROWS_PER_SCRIPT); row++) {
                        // o começo muda por linha para a compressão não encolher a base
                        script.append("CREATE bulk:").append(row).append(" CONTENT { n: '")
                            .append(java.util.UUID.randomUUID()).append(filler).append("' };");
                    }
                    GateClient.Reply reply = client.sql(script.toString());
                    if (reply.verdict() != GateClient.Verdict.DONE) {
                        failures.add(reply.status() + " " + reply.body());
                    }
                }
            }));
        }
        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException interrupted) {
                throw new IllegalStateException(interrupted);
            }
        }
        if (!failures.isEmpty()) {
            throw new IllegalStateException("carga falhou: " + failures.peek());
        }
        out.println("E5 dr loaded rows=" + rows + " seconds=" + (System.nanoTime() - started) / 1_000_000_000L);
    }

    /**
     * Restaura cada nó do arquivamento, sobe o cluster e mede o tempo até a primeira escrita. Confere que o que foi
     * confirmado bem antes do instante está lá e que o que foi enviado bem depois não está.
     */
    private boolean restoreAndCheck(List<Node> nodes, GateClient client, String store, String epoch, String at,
                                    String settings, Queue<Write> confirmed, long instant, String label)
            throws Exception {
        for (Node node : nodes) {
            agent(node, "/stop");
        }
        long started = System.currentTimeMillis();
        List<Thread> restores = new ArrayList<>();
        Queue<String> failures = new ConcurrentLinkedQueue<>();
        for (Node node : nodes) {
            restores.add(Thread.ofPlatform().start(() -> {
                try {
                    agent(node, "/restore?store=" + encode(store) + "&epoch=" + encode(epoch) + "&at=" + encode(at))
                        .lines().forEach(line -> out.println("E5 dr " + label + " node=" + node.host() + " " + line));
                } catch (Exception failure) {
                    failures.add(String.valueOf(failure.getMessage()));
                }
            }));
        }
        for (Thread thread : restores) {
            thread.join();
        }
        if (!failures.isEmpty()) {
            out.println("E5 dr " + label + " restore_failed=" + failures.peek());
            return false;
        }
        for (Node node : nodes) {
            agent(node, startPath(nodes, node, "&data=restored" + settings));
        }
        require(client.sql("CREATE bulk:`prova-" + label + "-" + started + "` CONTENT { n: 'depois' }"));
        long recovery = System.currentTimeMillis() - started;
        Set<String> present = keys(leader(nodes, client), client);
        long missing = confirmed.stream()
            .filter(w -> instant == Long.MAX_VALUE || w.ackedMillis() < instant - CLOCK_MARGIN_MILLIS)
            .filter(w -> !present.contains(w.key())).count();
        long extra = confirmed.stream()
            .filter(w -> instant != Long.MAX_VALUE && w.sentMillis() > instant + CLOCK_MARGIN_MILLIS)
            .filter(w -> present.contains(w.key())).count();
        boolean passed = recovery < MAXIMUM_RTO_MILLIS && missing == 0 && extra == 0 && !present.isEmpty();
        out.println("E5 dr " + label + " at=" + at + " rto_ms=" + recovery + " rows=" + present.size()
            + " missing=" + missing + " after_instant=" + extra + (passed ? " PASS" : " FAIL"));
        return passed;
    }
}
