package com.axonbase.server;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Portão de carga da fase E3 (opt-in, fora da suíte): vazão de ingestão pelo gateway. Rode contra um conjunto de um
 * shard e contra um de três, em máquinas separadas, e compare. Como usar: {@code scripts/shard-gates/run.sh}.
 *
 * <pre>
 *   measure --gateways http://g1:8080[,http://g2:8080] --label um-shard --out um.json
 *           [--threads 64] [--seconds 60] [--warmup 15] [--batch 1] [--payload 200] [--table carga_x]
 *           [--user root --pass segredo]
 *   compare --baseline um.json --sharded tres.json [--min 2.4]
 * </pre>
 *
 * <p>Cada requisição grava {@code batch} linhas de um mesmo cliente sorteado, então cai num shard só. O resultado só
 * vale se, ao fim, a contagem na tabela bater com as linhas confirmadas.</p>
 */
public final class ShardIngestGate {

    private static final double MINIMUM_RATIO = 2.4;
    private static final String NAMESPACE = "gate";
    private static final String DATABASE = "e3";

    private ShardIngestGate() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
        }
        Map<String, String> options = options(args);
        switch (args[0]) {
            case "measure" -> measure(options);
            case "compare" -> compare(options);
            default -> usage();
        }
    }

    private static void usage() {
        System.err.println("uso: measure --gateways <urls> --label <nome> --out <arquivo> [opções] "
            + "| compare --baseline <arquivo> --sharded <arquivo> [--min 2.4]");
        System.exit(2);
    }

    private static Map<String, String> options(String[] args) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 1; i < args.length; i += 2) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                usage();
            }
            out.put(args[i].substring(2), args[i + 1]);
        }
        return out;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            usage();
        }
        return value;
    }

    private static int number(Map<String, String> options, String name, int fallback) {
        return options.containsKey(name) ? Integer.parseInt(options.get(name)) : fallback;
    }

    private static void measure(Map<String, String> options) throws Exception {
        List<String> gateways = List.of(required(options, "gateways").split(","));
        String label = required(options, "label");
        Path out = Path.of(required(options, "out"));
        int threads = number(options, "threads", 64);
        int seconds = number(options, "seconds", 60);
        int warmup = number(options, "warmup", 15);
        int batch = number(options, "batch", 1);
        int payload = number(options, "payload", 200);
        // uma tabela nova a cada medida: a contagem final só vê as linhas desta rodada
        String table = options.getOrDefault("table", "carga_" + System.currentTimeMillis());
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .version(HttpClient.Version.HTTP_1_1).build()) {
            String token = options.containsKey("user")
                ? signin(http, gateways.get(0), options.get("user"), options.getOrDefault("pass", "")) : null;
            expectOk(post(http, gateways.get(0), token, "DEFINE TABLE " + table + " SCHEMALESS SHARD BY cliente"));

            LongAdder confirmed = new LongAdder();
            LongAdder measured = new LongAdder();
            LongAdder failures = new LongAdder();
            LongAdder unknown = new LongAdder();
            AtomicReference<String> firstFailure = new AtomicReference<>();
            long start = System.nanoTime();
            long measureFrom = start + Duration.ofSeconds(warmup).toNanos();
            long end = measureFrom + Duration.ofSeconds(seconds).toNanos();
            String filler = "x".repeat(payload);
            List<Thread> workers = new ArrayList<>(threads);
            for (int w = 0; w < threads; w++) {
                String gateway = gateways.get(w % gateways.size());
                workers.add(Thread.ofVirtual().start(() -> {
                    while (System.nanoTime() < end) {
                        String sql = insert(table, batch, filler);
                        try {
                            HttpResponse<String> response = post(http, gateway, token, sql);
                            if (response.statusCode() == 200) {
                                confirmed.add(batch);
                                long now = System.nanoTime();
                                if (now >= measureFrom && now < end) {
                                    measured.add(batch);
                                }
                            } else {
                                (response.statusCode() == 504 ? unknown : failures).add(1);
                                firstFailure.compareAndSet(null, response.statusCode() + " " + response.body());
                            }
                        } catch (IOException e) {
                            unknown.add(1);
                            firstFailure.compareAndSet(null, e.toString());
                        } catch (InterruptedException e) {
                            return;
                        }
                    }
                }));
            }
            for (Thread worker : workers) {
                worker.join();
            }
            HttpResponse<String> counted =
                expectOk(post(http, gateways.get(0), token, "SELECT count() FROM " + table + " GROUP ALL"));
            long stored = AxonJson.parseDocument(counted.body()).asArray().get(0).asObject().get("count").asLong();
            // uma requisição de desfecho desconhecido pode ter gravado
            boolean consistent = stored >= confirmed.sum() && stored <= confirmed.sum() + unknown.sum() * batch;
            double rate = (double) measured.sum() / seconds;

            Map<String, AxonValue> result = new LinkedHashMap<>();
            result.put("label", AxonValue.str(label));
            result.put("rowsPerSecond", AxonValue.num(rate));
            result.put("confirmed", AxonValue.num(confirmed.sum()));
            result.put("stored", AxonValue.num(stored));
            result.put("failures", AxonValue.num(failures.sum()));
            result.put("unknown", AxonValue.num(unknown.sum()));
            result.put("consistent", AxonValue.bool(consistent));
            result.put("threads", AxonValue.num(threads));
            result.put("batch", AxonValue.num(batch));
            result.put("payload", AxonValue.num(payload));
            result.put("seconds", AxonValue.num(seconds));
            Files.writeString(out, AxonJson.write(AxonValue.object(result)), StandardCharsets.UTF_8);
            System.out.printf(Locale.ROOT,
                "GATE_E3 MEASURE label=%s rows_per_second=%.0f confirmed=%d stored=%d failures=%d unknown=%d "
                    + "consistent=%s%n",
                label, rate, confirmed.sum(), stored, failures.sum(), unknown.sum(), consistent);
            if (firstFailure.get() != null) {
                System.out.println("GATE_E3 FIRST_FAILURE " + firstFailure.get());
            }
            if (!consistent) {
                System.exit(1);
            }
        }
    }

    private static String insert(String table, int batch, String filler) {
        String client = "c" + ThreadLocalRandom.current().nextInt(1_000_000);
        StringBuilder sql = new StringBuilder("INSERT INTO ").append(table).append(" [");
        for (int i = 0; i < batch; i++) {
            sql.append(i == 0 ? "" : ",").append("{cliente:\"").append(client).append("\",total:")
                .append(ThreadLocalRandom.current().nextInt(10_000)).append(",dados:\"").append(filler).append("\"}");
        }
        return sql.append("] RETURN NONE").toString();
    }

    private static HttpResponse<String> post(HttpClient http, String gateway, String token, String sql)
        throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(gateway.replaceAll("/+$", "") + "/sql"))
            .timeout(Duration.ofSeconds(30))
            .header("Axon-Ns", NAMESPACE).header("Axon-Db", DATABASE)
            .POST(HttpRequest.BodyPublishers.ofString(sql));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpResponse<String> expectOk(HttpResponse<String> response) {
        if (response.statusCode() != 200) {
            throw new IllegalStateException("o gateway respondeu " + response.statusCode() + ": " + response.body());
        }
        return response;
    }

    private static String signin(HttpClient http, String gateway, String user, String pass)
        throws IOException, InterruptedException {
        Map<String, AxonValue> body = new LinkedHashMap<>();
        body.put("user", AxonValue.str(user));
        body.put("pass", AxonValue.str(pass));
        HttpResponse<String> response = expectOk(http.send(
            HttpRequest.newBuilder(URI.create(gateway.replaceAll("/+$", "") + "/signin"))
                .POST(HttpRequest.BodyPublishers.ofString(AxonJson.write(AxonValue.object(body)))).build(),
            HttpResponse.BodyHandlers.ofString()));
        return AxonJson.parseDocument(response.body()).asObject().get("token").asString();
    }

    private static void compare(Map<String, String> options) throws IOException {
        Map<String, AxonValue> baseline = read(required(options, "baseline"));
        Map<String, AxonValue> sharded = read(required(options, "sharded"));
        double minimum = options.containsKey("min") ? Double.parseDouble(options.get("min")) : MINIMUM_RATIO;
        double one = baseline.get("rowsPerSecond").asDouble();
        double many = sharded.get("rowsPerSecond").asDouble();
        boolean comparable = baseline.get("batch").equals(sharded.get("batch"))
            && baseline.get("payload").equals(sharded.get("payload"));
        boolean consistent = baseline.get("consistent").asBool() && sharded.get("consistent").asBool();
        double ratio = one > 0 ? many / one : 0;
        boolean pass = comparable && consistent && ratio >= minimum;
        System.out.printf(Locale.ROOT,
            "GATE_E3 RESULT baseline=%.0f sharded=%.0f ratio=%.2f minimum=%.2f comparable=%s consistent=%s %s%n",
            one, many, ratio, minimum, comparable, consistent, pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private static Map<String, AxonValue> read(String file) throws IOException {
        return AxonJson.parseDocument(Files.readString(Path.of(file), StandardCharsets.UTF_8)).asObject();
    }
}
