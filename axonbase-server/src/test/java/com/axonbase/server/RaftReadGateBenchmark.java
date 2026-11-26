package com.axonbase.server;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Portão 2 da E2: gerador de leituras HTTP contra um ou vários nós. Cada resposta é conferida: só conta como leitura
 * a que traz exatamente a linha pedida.
 *
 * <p>Uso: {@code RaftReadGateBenchmark load <url,...> <linhas>} grava as linhas no líder (ou no nó único) e espera
 * todos os nós as terem; {@code RaftReadGateBenchmark read <url,...> <linhas> <conexões> <segundos> [consistência]}
 * lê registros por id, com as conexões divididas entre os endereços, e imprime uma linha {@code GATE2}. Sai com 1
 * quando alguma resposta não serve.</p>
 */
public final class RaftReadGateBenchmark {

    private static final int WARMUP_SECONDS = Integer.getInteger("axon.gate.read.warmup", 10);
    private static final int LOADERS = 32;
    private static final String TEXT = "x".repeat(200);
    private static final int SAMPLES = 5;
    private static final Duration CATCH_UP = Duration.ofMinutes(2);

    /** O que uma resposta do {@code /sql} trouxe para a linha pedida. */
    enum Answer { ROW, EMPTY, WRONG }

    /**
     * Uma medida: vazão das respostas certas e quantas não serviram, do aquecimento ao fim (erro, vazia ou com
     * conteúdo diferente do pedido).
     */
    record Measure(double opsPerSecond, long failed, long empty, long wrong, double generatorCpu) {
        long errors() {
            return failed + empty + wrong;
        }
    }

    private RaftReadGateBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.ROOT);
        List<String> urls = List.of(args[1].split(","));
        int rows = Integer.parseInt(args[2]);
        long errors = 0;
        if (args[0].equals("load")) {
            load(urls, rows);
        } else {
            errors = read(urls, rows, Integer.parseInt(args[3]), Integer.parseInt(args[4]),
                args.length > 5 ? args[5] : null).errors();
        }
        System.exit(errors == 0 ? 0 : 1);
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

    private static String send(HttpClient http, HttpRequest request) throws Exception {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IllegalStateException(response.statusCode() + " " + cut(response.body()));
        }
        return response.body();
    }

    private static String cut(String body) {
        return body.length() <= 300 ? body : body.substring(0, 300) + "...";
    }

    /**
     * As linhas de uma resposta do {@code /sql}, ou {@code null} quando ela não traz linhas. Um {@code SELECT} por
     * id com um resultado devolve o objeto sem lista em volta.
     */
    private static List<AxonValue> resultRows(String body) {
        try {
            AxonValue value = AxonJson.parseDocument(body);
            // o resultado pode vir embrulhado em uma lista de resultados por instrução
            while (value.isArray() && value.asArray().size() == 1 && value.asArray().getFirst().isArray()) {
                value = value.asArray().getFirst();
            }
            if (value.isObject()) {
                return List.of(value);
            }
            return value.isArray() ? value.asArray() : null;
        } catch (RuntimeException notJson) {
            return null;
        }
    }

    /** Confere se a resposta traz exatamente a linha {@code item:i<id>} gravada pela carga. */
    static Answer answer(String body, long id) {
        List<AxonValue> rows = resultRows(body);
        if (rows == null) {
            return Answer.WRONG;
        }
        if (rows.isEmpty()) {
            return Answer.EMPTY;
        }
        if (rows.size() != 1 || !rows.getFirst().isObject()) {
            return Answer.WRONG;
        }
        Map<String, AxonValue> row = rows.getFirst().asObject();
        AxonValue number = row.get("n");
        AxonValue note = row.get("nota");
        boolean same = number != null && number.isNumber() && number.asLong() == id
            && note != null && note.isString() && note.asString().equals(TEXT);
        return same ? Answer.ROW : Answer.WRONG;
    }

    /** O total de um {@code count() AS n ... GROUP ALL}, ou -1 quando a resposta não é um total. */
    private static long counted(String body) {
        List<AxonValue> rows = resultRows(body);
        if (rows == null) {
            return -1;
        }
        if (rows.isEmpty()) {
            return 0;
        }
        AxonValue number = rows.getFirst().isObject() ? rows.getFirst().asObject().get("n") : null;
        return number != null && number.isNumber() ? number.asLong() : -1;
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

    /** Grava {@code rows} linhas e só volta quando todos os nós as têm; falha se alguma escrita falhar. */
    static void load(List<String> urls, int rows) throws Exception {
        HttpClient http = client();
        String leader = leader(http, urls);
        AtomicLong next = new AtomicLong();
        AtomicLong failed = new AtomicLong();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < LOADERS; t++) {
            workers.add(Thread.ofVirtual().start(() -> {
                for (long i = next.getAndIncrement(); i < rows; i = next.getAndIncrement()) {
                    try {
                        send(http, sql(leader,
                            "CREATE item:i" + i + " CONTENT { n: " + i + ", nota: '" + TEXT + "' }", null));
                    } catch (Exception e) {
                        if (failed.incrementAndGet() <= SAMPLES) {
                            System.out.println("GATE2 sample load id=" + i + " " + e.getMessage());
                        }
                    }
                }
            }));
        }
        for (Thread worker : workers) {
            worker.join();
        }
        System.out.println("GATE2 load rows=" + rows + " failed=" + failed.get());
        if (failed.get() > 0) {
            throw new IllegalStateException("carga incompleta: " + failed.get() + " de " + rows + " escritas falharam");
        }
        awaitRows(urls, rows, CATCH_UP);
    }

    /** Espera cada nó ter exatamente {@code rows} linhas e confere o conteúdo da última. */
    static void awaitRows(List<String> urls, int rows, Duration limit) throws Exception {
        HttpClient http = client();
        for (String url : urls) {
            long deadline = System.nanoTime() + limit.toNanos();
            while (true) {
                String body = send(http, sql(url, "SELECT count() AS n FROM item GROUP ALL", null));
                long found = counted(body);
                if (found == rows) {
                    break;
                }
                if (found > rows || System.nanoTime() >= deadline) {
                    throw new IllegalStateException(url + " não tem as " + rows + " linhas da carga: " + cut(body));
                }
                Thread.sleep(500);
            }
            String last = send(http, sql(url, "SELECT * FROM item:i" + (rows - 1), null));
            if (answer(last, rows - 1) != Answer.ROW) {
                throw new IllegalStateException(url + " devolve outra coisa para a última linha: " + cut(last));
            }
            System.out.println("GATE2 check " + url + " rows=" + rows);
        }
    }

    static Measure read(List<String> urls, int rows, int connections, int seconds, String consistency)
            throws Exception {
        return read(urls, rows, connections, WARMUP_SECONDS, seconds, consistency);
    }

    /** O que as conexões de uma medida compartilham. */
    private record Tally(AtomicBoolean running, AtomicBoolean measuring, AtomicLong done, AtomicLong failed,
            AtomicLong empty, AtomicLong wrong, AtomicLong samples) {
        Tally() {
            this(new AtomicBoolean(true), new AtomicBoolean(), new AtomicLong(), new AtomicLong(), new AtomicLong(),
                new AtomicLong(), new AtomicLong());
        }
    }

    /**
     * Lê ids sorteados com {@code connections} conexões, divididas entre os endereços, cada uma com a sua thread e um
     * pedido por vez. A medida traz o processador usado nesta máquina: com ele perto do total, o número é o do
     * gerador.
     */
    static Measure read(List<String> urls, int rows, int connections, int warmup, int seconds, String consistency)
            throws Exception {
        Tally tally = new Tally();
        String headers = "Axon-Ns: app\r\nAxon-Db: main\r\n"
            + (consistency == null ? "" : "Axon-Read-Consistency: " + consistency + "\r\n");
        List<Thread> workers = new ArrayList<>();
        for (int c = 0; c < connections; c++) {
            String url = urls.get(c % urls.size());
            workers.add(Thread.ofPlatform().daemon().start(() -> readLoop(url, headers, rows, tally)));
        }
        Thread.sleep(warmup * 1_000L);
        var machine = (com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        machine.getCpuLoad(); // a chamada seguinte devolve o uso desde esta
        long from = System.nanoTime();
        tally.measuring().set(true);
        Thread.sleep(seconds * 1_000L);
        tally.measuring().set(false);
        double elapsed = (System.nanoTime() - from) / 1e9;
        double cpu = machine.getCpuLoad();
        long count = tally.done().get();
        tally.running().set(false);
        for (Thread worker : workers) {
            worker.join(60_000);
        }
        Measure measure = new Measure(count / elapsed, tally.failed().get(), tally.empty().get(), tally.wrong().get(),
            cpu);
        System.out.printf("GATE2 read nodes=%d connections=%d consistency=%s ops_s=%.0f failed=%d empty=%d wrong=%d "
                + "generator_cpu=%.2f%n", urls.size(), connections, consistency == null ? "padrao" : consistency,
            measure.opsPerSecond(), measure.failed(), measure.empty(), measure.wrong(), measure.generatorCpu());
        return measure;
    }

    private static void readLoop(String url, String headers, int rows, Tally tally) {
        LeanHttpConnection connection = null;
        while (tally.running().get()) {
            long id = ThreadLocalRandom.current().nextLong(rows);
            String problem;
            AtomicLong kind;
            try {
                if (connection == null || connection.closing()) {
                    close(connection);
                    connection = new LeanHttpConnection(url, headers);
                }
                LeanHttpConnection.Response response = connection.post("/sql", "SELECT * FROM item:i" + id);
                Answer got = response.status() == 200 ? answer(response.body(), id) : null;
                if (got == Answer.ROW) {
                    if (tally.measuring().get()) {
                        tally.done().incrementAndGet();
                    }
                    continue;
                }
                kind = got == null ? tally.failed() : got == Answer.EMPTY ? tally.empty() : tally.wrong();
                problem = response.status() + " " + cut(response.body());
            } catch (IOException | RuntimeException e) {
                close(connection);
                connection = null;
                kind = tally.failed();
                problem = e.toString();
            }
            if (!tally.running().get()) {
                break; // pedido interrompido pelo fim da medida
            }
            kind.incrementAndGet();
            if (tally.samples().incrementAndGet() <= SAMPLES) {
                System.out.println("GATE2 sample " + url + " id=" + id + " " + problem);
            }
            if (connection == null) {
                LockSupport.parkNanos(10_000_000); // sem conexão, não gira em falso
            }
        }
        close(connection);
    }

    private static void close(LeanHttpConnection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (IOException ignored) {
                // já está fechada
            }
        }
    }
}
