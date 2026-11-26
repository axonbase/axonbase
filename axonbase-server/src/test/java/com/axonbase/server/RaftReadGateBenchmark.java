package com.axonbase.server;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Portão 2 da E2: gerador de leituras HTTP contra um ou vários nós.
 *
 * <p>Uso: {@code RaftReadGateBenchmark load <url,...> <linhas>} grava as linhas no líder (ou no nó único);
 * {@code RaftReadGateBenchmark read <url,...> <linhas> <conexões> <segundos> [consistência]} lê registros
 * por id, com as conexões divididas entre os endereços, e imprime uma linha {@code GATE2}.</p>
 */
public final class RaftReadGateBenchmark {

    private static final int WARMUP_SECONDS = 10;
    private static final int LOADERS = 32;

    private RaftReadGateBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        List<String> urls = List.of(args[1].split(","));
        int rows = Integer.parseInt(args[2]);
        if (args[0].equals("load")) {
            load(urls, rows);
        } else {
            read(urls, rows, Integer.parseInt(args[3]), Integer.parseInt(args[4]), args.length > 5 ? args[5] : null);
        }
        System.exit(0);
    }

    private static HttpClient client() {
        return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5))
            .build();
    }

    private static HttpRequest sql(String url, String text, String consistency) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url + "/sql"))
            .timeout(Duration.ofSeconds(30))
            .header("Axon-Ns", "app").header("Axon-Db", "main")
            .POST(HttpRequest.BodyPublishers.ofString(text));
        if (consistency != null) {
            request.header("Axon-Read-Consistency", consistency);
        }
        return request.build();
    }

    /** O nó que se diz líder; um servidor sem cluster responde como líder. */
    private static String leader(HttpClient http, List<String> urls) throws Exception {
        for (int attempt = 0; attempt < 120; attempt++) {
            for (String url : urls) {
                try {
                    String status = http.send(HttpRequest.newBuilder(URI.create(url + "/status")).build(),
                        HttpResponse.BodyHandlers.ofString()).body();
                    if (status.contains("\"role\":\"LEADER\"")) {
                        return url;
                    }
                } catch (java.io.IOException starting) {
                    // o nó ainda está subindo
                }
            }
            Thread.sleep(500);
        }
        throw new IllegalStateException("nenhum líder em " + urls);
    }

    private static void load(List<String> urls, int rows) throws Exception {
        HttpClient http = client();
        String leader = leader(http, urls);
        String text = "x".repeat(200);
        AtomicLong next = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < LOADERS; t++) {
            workers.add(Thread.ofVirtual().start(() -> {
                for (long i = next.getAndIncrement(); i < rows; i = next.getAndIncrement()) {
                    try {
                        HttpResponse<String> response = http.send(sql(leader,
                            "CREATE item:i" + i + " CONTENT { n: " + i + ", nota: '" + text + "' }", null),
                            HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() != 200 && failed.incrementAndGet() < 5) {
                            System.out.println("load " + response.statusCode() + " " + response.body());
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    }
                }
            }));
        }
        for (Thread worker : workers) {
            worker.join();
        }
        System.out.println("GATE2 load rows=" + rows + " failed=" + failed.get());
        Thread.sleep(2_000);
        for (String url : urls) {
            String body = http.send(sql(url, "SELECT * FROM item:i" + (rows - 1), null),
                HttpResponse.BodyHandlers.ofString()).body();
            System.out.println("GATE2 check " + url + " tem_a_ultima_linha=" + body.contains("\"n\":" + (rows - 1)));
        }
    }

    private static void read(List<String> urls, int rows, int connections, int seconds, String consistency)
            throws Exception {
        AtomicBoolean measuring = new AtomicBoolean();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong done = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        AtomicLong empty = new AtomicLong();
        List<Thread> workers = new ArrayList<>();
        // Um cliente por endereço, para as conexões não disputarem um único seletor.
        List<HttpClient> clients = urls.stream().map(u -> client()).toList();
        for (int c = 0; c < connections; c++) {
            String url = urls.get(c % urls.size());
            HttpClient http = clients.get(c % urls.size());
            workers.add(Thread.ofVirtual().start(() -> {
                while (running.get()) {
                    try {
                        HttpResponse<String> response = http.send(sql(url, "SELECT * FROM item:i"
                            + ThreadLocalRandom.current().nextInt(rows), consistency),
                            HttpResponse.BodyHandlers.ofString());
                        if (!measuring.get()) {
                            continue;
                        }
                        if (response.statusCode() != 200) {
                            failed.incrementAndGet();
                        } else if (!response.body().contains("\"n\":")) {
                            empty.incrementAndGet();
                        } else {
                            done.incrementAndGet();
                        }
                    } catch (Exception e) {
                        failed.incrementAndGet();
                    }
                }
            }));
        }
        Thread.sleep(WARMUP_SECONDS * 1_000L);
        long from = System.nanoTime();
        measuring.set(true);
        Thread.sleep(seconds * 1_000L);
        measuring.set(false);
        double elapsed = (System.nanoTime() - from) / 1e9;
        long count = done.get();
        running.set(false);
        for (Thread worker : workers) {
            worker.join(60_000);
        }
        System.out.printf("GATE2 read nodes=%d connections=%d consistency=%s ops_s=%.0f failed=%d empty=%d%n",
            urls.size(), connections, consistency == null ? "padrao" : consistency, count / elapsed, failed.get(),
            empty.get());
    }
}
