package com.axonbase.server;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Controlador dos portões da E2, na máquina geradora de carga. Fala com um {@link RaftGateNode} em cada nó.
 *
 * <pre>
 * RaftRemoteGates gate1 --nodes ip1,ip2,ip3 [--writers 8,64,256] [--seconds 30] [--rounds 3] [--warmup 10]
 * RaftRemoteGates gate2 --nodes ip1,ip2,ip3 [--rows 100000] [--connections 192] [--seconds 30] [--rounds 3]
 * RaftRemoteGates gate3 --nodes ip1,ip2,ip3 --new ip4 [--gb 10] [--rate 1000] [--baseline 60] [--source follower]
 * </pre>
 *
 * <p>No portão 1 o modo vale para o armazenamento e para o log Raft de cada nó, explicitamente: o caso
 * {@code always} sincroniza os dois a cada escrita e o caso {@code batch}, nenhum. {@code AXON_STORAGE_SYNC} e
 * {@code AXON_RAFT_LOG_SYNC} no ambiente dos agentes não mudam a comparação.</p>
 *
 * <p>Um endereço pode levar um deslocamento de portas ({@code 127.0.0.1+10}), para ensaiar tudo numa máquina.
 * Cada linha de resultado começa com {@code GATE1}, {@code GATE2} ou {@code GATE3}; o veredito vem nas linhas
 * {@code GATEn RESULT}. O processo sai com 1 quando um portão reprova.</p>
 */
public final class RaftRemoteGates {

    private static final double GATE1_MINIMUM_RATIO = 0.60;
    private static final int GATE1_WRITERS = 64;
    private static final double GATE2_MINIMUM_RATIO = 2.5;
    private static final double GATE3_MAXIMUM_SECONDS = 15 * 60;
    private static final double GATE3_MAXIMUM_GAP_MILLIS = 1_000;
    private static final int LOAD_ATTEMPTS = 10;
    private static final double GENERATOR_BUSY = 0.80;

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final String token;
    private final Map<String, String> options;
    private boolean failed;

    private record Node(String host, int offset) {
        static Node parse(String spec) {
            int plus = spec.indexOf('+');
            return plus < 0 ? new Node(spec.trim(), 0)
                : new Node(spec.substring(0, plus).trim(), Integer.parseInt(spec.substring(plus + 1).trim()));
        }

        String control() {
            return "http://" + host + ":" + (RaftGateNode.CONTROL_PORT + offset);
        }

        String sql() {
            return "http://" + host + ":" + (RaftGateNode.HTTP_PORT + offset);
        }

        String raft() {
            return host + ":" + (RaftGateNode.RAFT_PORT + offset);
        }

        @Override
        public String toString() {
            return host + (offset == 0 ? "" : "+" + offset);
        }
    }

    private RaftRemoteGates(String token, Map<String, String> options) {
        this.token = token;
        this.options = options;
    }

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        RaftReadGateBenchmark.closeIdleConnectionsBeforeTheServers();
        String token = System.getenv("AXON_GATE_TOKEN");
        Map<String, String> options = RaftGateNode.options(args, 1);
        if (args.length == 0 || token == null || token.isBlank() || !options.containsKey("nodes")) {
            System.err.println("uso: AXON_GATE_TOKEN=... RaftRemoteGates gate1|gate2|gate3 --nodes ip1,ip2,ip3 ...");
            System.exit(2);
        }
        RaftRemoteGates gates = new RaftRemoteGates(token, options);
        List<Node> nodes = Arrays.stream(options.get("nodes").split(",")).map(Node::parse).toList();
        try {
            switch (args[0]) {
                case "gate1" -> gates.gate1(nodes);
                case "gate2" -> gates.gate2(nodes);
                case "gate3" -> gates.gate3(nodes, Node.parse(options.get("new")));
                default -> throw new IllegalArgumentException("portão desconhecido: " + args[0]);
            }
        } finally {
            for (Node node : nodes) {
                gates.quietly(node, "/stop");
            }
            if (options.containsKey("new")) {
                gates.quietly(Node.parse(options.get("new")), "/stop");
            }
        }
        System.exit(gates.failed ? 1 : 0);
    }

    private int option(String name, int fallback) {
        return Integer.parseInt(options.getOrDefault(name, Integer.toString(fallback)));
    }

    // ---- portão 1 ----

    private void gate1(List<Node> nodes) throws Exception {
        List<Integer> writers = Arrays.stream(options.getOrDefault("writers", "8,64,256").split(","))
            .map(w -> Integer.parseInt(w.trim())).toList();
        int seconds = option("seconds", 30);
        int rounds = option("rounds", 3);
        int warmup = option("warmup", 10);
        Map<String, List<Double>> results = new LinkedHashMap<>();
        long elections = 0;
        for (int round = 1; round <= rounds; round++) {
            for (String mode : List.of("always", "batch")) {
                System.out.println("GATE1 round=" + round + " mode=" + mode);
                startCluster(nodes, mode, "");
                long before = elections(nodes);
                for (int count : writers) {
                    for (String scenario : List.of("raw", "sql")) {
                        String line = call(awaitLeader(nodes), "/write?writers=" + count + "&seconds=" + seconds
                            + "&warmup=" + warmup + "&scenario=" + scenario, seconds + warmup + 180);
                        System.out.println(line);
                        results.computeIfAbsent(scenario + " " + count + " " + mode, k -> new ArrayList<>())
                            .add(field(line, "ops_s"));
                    }
                }
                long during = elections(nodes) - before;
                System.out.println("GATE1 round=" + round + " mode=" + mode + " elections=" + during);
                elections += during;
                stopAll(nodes);
            }
        }
        electionVerdict("GATE1", elections);
        for (String scenario : List.of("raw", "sql")) {
            for (int count : writers) {
                double always = median(results.get(scenario + " " + count + " always"));
                double batch = median(results.get(scenario + " " + count + " batch"));
                double ratio = always / batch;
                String verdict = count != GATE1_WRITERS ? "INFO" : verdict(ratio >= GATE1_MINIMUM_RATIO);
                System.out.printf("GATE1 RESULT scenario=%s writers=%d always=%.0f batch=%.0f ratio=%.3f "
                    + "target=%.2f %s%n", scenario, count, always, batch, ratio, GATE1_MINIMUM_RATIO, verdict);
            }
        }
    }

    // ---- portão 2 ----

    private void gate2(List<Node> nodes) throws Exception {
        int rows = option("rows", 100_000);
        int connections = option("connections", 192);
        int seconds = option("seconds", 30);
        int rounds = option("rounds", 3);

        Node solo = nodes.getFirst();
        System.out.println(call(solo, "/start?role=solo&mode=batch", 120));
        RaftReadGateBenchmark.load(List.of(solo.sql()), rows);
        List<RaftReadGateBenchmark.Measure> single = new ArrayList<>();
        for (int round = 0; round < rounds; round++) {
            single.add(read(List.of(solo), rows, connections, seconds, null));
        }
        call(solo, "/stop", 120);

        startCluster(nodes, "batch", "");
        long before = elections(nodes);
        List<String> urls = nodes.stream().map(Node::sql).toList();
        Map<String, List<RaftReadGateBenchmark.Measure>> clustered = new LinkedHashMap<>();
        try {
            RaftReadGateBenchmark.load(urls, rows);
            for (int round = 0; round < rounds; round++) {
                for (String consistency : new String[] {null, "max-lag=1000", "linearizable"}) {
                    clustered.computeIfAbsent(consistency == null ? "padrao" : consistency, k -> new ArrayList<>())
                        .add(read(nodes, rows, connections, seconds, consistency));
                }
            }
        } finally {
            checkElections("GATE2", nodes, before);
        }
        double base = medianOps(single);
        long soloErrors = errors(single);
        clustered.forEach((consistency, measures) -> {
            double ratio = medianOps(measures) / base;
            long errors = soloErrors + errors(measures);
            // Com resposta que não serve não há vazão para comparar: reprova em qualquer consistência.
            String verdict = errors > 0 ? verdict(false)
                : consistency.equals("padrao") ? verdict(ratio >= GATE2_MINIMUM_RATIO) : "INFO";
            double generator = Math.max(busiestGenerator(single), busiestGenerator(measures));
            System.out.printf("GATE2 RESULT consistency=%s single=%.0f three=%.0f ratio=%.2f target=%.1f errors=%d "
                    + "generator_cpu=%.2f %s%n", consistency, base, medianOps(measures), ratio, GATE2_MINIMUM_RATIO,
                errors, generator, verdict);
            if (generator >= GENERATOR_BUSY) {
                System.out.printf("GATE2 warning consistency=%s o gerador usou %.0f%% do processador: a razão mede o "
                    + "gerador, não o cluster; repita numa máquina maior%n", consistency, generator * 100);
            }
        });
    }

    /** Uma medida de leitura contra {@code targets}, com o processador usado em cada nó durante ela. */
    private RaftReadGateBenchmark.Measure read(List<Node> targets, int rows, int connections, int seconds,
            String consistency) throws Exception {
        for (Node node : targets) {
            call(node, "/cpu", 60); // a próxima chamada devolve o uso desde esta
        }
        RaftReadGateBenchmark.Measure measure = RaftReadGateBenchmark.read(targets.stream().map(Node::sql).toList(),
            rows, connections, seconds, consistency);
        StringBuilder line = new StringBuilder("GATE2 cpu");
        for (Node node : targets) {
            line.append(' ').append(node).append('=').append(word(call(node, "/cpu", 60), "cpu"));
        }
        System.out.println(line);
        return measure;
    }

    private static double busiestGenerator(List<RaftReadGateBenchmark.Measure> measures) {
        return measures.stream().mapToDouble(RaftReadGateBenchmark.Measure::generatorCpu).max().orElse(0);
    }

    private static double medianOps(List<RaftReadGateBenchmark.Measure> measures) {
        return median(measures.stream().map(RaftReadGateBenchmark.Measure::opsPerSecond).toList());
    }

    private static long errors(List<RaftReadGateBenchmark.Measure> measures) {
        return measures.stream().mapToLong(RaftReadGateBenchmark.Measure::errors).sum();
    }

    // ---- portão 3 ----

    private void gate3(List<Node> nodes, Node added) throws Exception {
        double gigabytes = Double.parseDouble(options.getOrDefault("gb", "10"));
        String mode = options.getOrDefault("mode", "batch");

        quietly(added, "/stop");
        startCluster(nodes, mode, added.raft());
        long before = elections(nodes);
        try {
            loadBase(nodes, gigabytes);
            awaitCaughtUp(nodes, awaitLeader(nodes), 0, 1_800);
            joinNewNode(nodes, added, mode, gigabytes);
        } finally {
            checkElections("GATE3", nodes, before);
        }
    }

    /** Carrega a base, retomando no líder da vez; uma troca de líder no meio é contada e reprova o portão. */
    private void loadBase(List<Node> nodes, double gigabytes) throws Exception {
        for (int attempt = 1; attempt <= LOAD_ATTEMPTS; attempt++) {
            Node leader = awaitLeader(nodes);
            try {
                String line = call(leader, "/load?gb=" + gigabytes, 3_600);
                System.out.println(line);
                if (line.contains(" complete=true")) {
                    return;
                }
            } catch (IllegalStateException refused) {
                // o nó deixou de ser o líder entre a consulta e o pedido
                System.out.println("GATE3 load attempt=" + attempt + " " + refused.getMessage());
            }
        }
        throw new IllegalStateException("carga incompleta depois de " + LOAD_ATTEMPTS + " tentativas");
    }

    private void joinNewNode(List<Node> nodes, Node added, String mode, double gigabytes) throws Exception {
        int rate = option("rate", 1_000);
        int baseline = option("baseline", 60);
        long rows = (long) (gigabytes * (1L << 30) / 4_096);

        Node leader = awaitLeader(nodes);
        Node source = options.getOrDefault("source", "follower").equals("leader") ? leader
            : nodes.stream().filter(n -> n != leader).findFirst().orElseThrow();
        System.out.println(call(leader, "/writer/start?rate=" + rate, 60));
        Thread.sleep(baseline * 1_000L);
        System.out.println(call(leader, "/writer/report?phase=baseline", 60));

        long started = System.nanoTime();
        System.out.println(call(source, "/backup", 3_600));
        System.out.println(call(added, "/join?mode=" + mode + "&peers=" + peers(nodes, null) + "&from="
            + URLEncoder.encode(source.control(), StandardCharsets.UTF_8), 3_600));
        // Em dia: a no máximo um décimo de segundo de escrita do líder.
        awaitCaughtUp(List.of(added), leader, Math.max(10, rate / 10), (long) GATE3_MAXIMUM_SECONDS * 2);
        double total = (System.nanoTime() - started) / 1e9;
        String during = call(leader, "/writer/report?phase=join", 60);
        System.out.println(during);
        String learner = call(added, "/status", 60);
        call(leader, "/writer/stop", 60);
        awaitCaughtUp(List.of(added), leader, 0, 300);
        String expected = call(leader, "/digest?rows=" + rows + "&count=5000", 600);
        String actual = call(added, "/digest?rows=" + rows + "&count=5000", 600);
        System.out.println(expected);
        System.out.println(actual);
        boolean same = word(expected, "sha256").equals(word(actual, "sha256")) && field(actual, "missing") == 0;
        double gap = field(during, "max_gap_ms");
        boolean passed = total <= GATE3_MAXIMUM_SECONDS && gap <= GATE3_MAXIMUM_GAP_MILLIS && same
            && field(learner, "snapshot_bytes") == 0;
        System.out.printf("GATE3 RESULT gigabytes=%.2f total_seconds=%.1f target=%.0f max_gap_ms=%.0f "
                + "target_ms=%.0f same_data=%s source=%s %s%n", gigabytes, total, GATE3_MAXIMUM_SECONDS, gap,
            GATE3_MAXIMUM_GAP_MILLIS, same, source == leader ? "leader" : "follower", verdict(passed));
    }

    // ---- eleições ----

    /** Eleições abertas pelos nós desde que subiram. */
    private long elections(List<Node> nodes) throws Exception {
        long total = 0;
        for (Node node : nodes) {
            total += (long) field(call(node, "/status", 60), "elections");
        }
        return total;
    }

    /** Os portões não injetam falha: qualquer eleição durante a medida reprova. */
    private void electionVerdict(String gate, long elections) {
        System.out.printf("%s RESULT elections=%d %s%n", gate, elections, verdict(elections == 0));
    }

    /** Como {@link #electionVerdict}, sem esconder a falha que interrompeu o portão se um nó não responder. */
    private void checkElections(String gate, List<Node> nodes, long before) {
        try {
            electionVerdict(gate, elections(nodes) - before);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (Exception unreachable) {
            System.out.printf("%s RESULT elections=desconhecido %s (%s)%n", gate, verdict(false),
                unreachable.getMessage());
        }
    }

    // ---- comando dos nós ----

    private void startCluster(List<Node> nodes, String mode, String learners) throws Exception {
        for (Node node : nodes) {
            call(node, "/stop", 120);
        }
        for (Node node : nodes) {
            System.out.println(call(node, "/start?role=voter&mode=" + mode + "&peers=" + peers(nodes, node)
                + "&learners=" + learners, 120));
        }
        awaitLeader(nodes);
    }

    private static String peers(List<Node> nodes, Node self) {
        return nodes.stream().filter(n -> n != self).map(Node::raft).collect(Collectors.joining(","));
    }

    private void stopAll(List<Node> nodes) throws Exception {
        for (Node node : nodes) {
            call(node, "/stop", 120);
        }
    }

    private Node awaitLeader(List<Node> nodes) throws Exception {
        long deadline = System.nanoTime() + Duration.ofMinutes(5).toNanos();
        while (System.nanoTime() < deadline) {
            for (Node node : nodes) {
                String status = call(node, "/status", 60);
                if (status.startsWith("role=LEADER ready=true")) {
                    return node;
                }
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("nenhum líder entre " + nodes);
    }

    private void awaitCaughtUp(List<Node> followers, Node leader, long margin, long seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        String last = "";
        while (System.nanoTime() < deadline) {
            double target = field(call(leader, "/status", 60), "commit");
            boolean all = true;
            for (Node node : followers) {
                last = call(node, "/status", 60);
                // ready: o nó conhece o líder; sem isso um backup recém-restaurado pareceria em dia.
                all &= last.contains("ready=true") && field(last, "commit") >= target - margin;
            }
            if (all) {
                return;
            }
            Thread.sleep(200);
        }
        throw new IllegalStateException("nós não alcançaram o líder em " + seconds + " s: " + last);
    }

    private String call(Node node, String path, long timeoutSeconds) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(node.control() + path))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header(RaftGateNode.TOKEN_HEADER, token)
            .build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(node + path + " -> " + response.statusCode() + " " + response.body());
        }
        return response.body();
    }

    private void quietly(Node node, String path) {
        try {
            call(node, path, 120);
        } catch (IOException | RuntimeException unreachable) {
            System.err.println("não foi possível chamar " + node + path + ": " + unreachable.getMessage());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- leitura das linhas ----

    private static String word(String line, String name) {
        for (String part : line.split("\\s+")) {
            if (part.startsWith(name + "=")) {
                return part.substring(name.length() + 1);
            }
        }
        throw new IllegalStateException("sem o campo " + name + " em: " + line);
    }

    private static double field(String line, String name) {
        return Double.parseDouble(word(line, name));
    }

    private static double median(List<Double> values) {
        List<Double> sorted = values.stream().sorted().toList();
        return sorted.get(sorted.size() / 2);
    }

    private String verdict(boolean passed) {
        failed |= !passed;
        return passed ? "PASS" : "FAIL";
    }
}
