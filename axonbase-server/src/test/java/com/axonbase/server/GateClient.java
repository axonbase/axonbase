package com.axonbase.server;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Um cliente HTTP que segue o contrato de repetição de {@code docs/operacao-e-resiliencia.md} e age como balanceador:
 * só manda pedidos a nós prontos, repete quando a escrita certamente não aconteceu e conta o resto como erro visível.
 */
final class GateClient implements AutoCloseable {

    /** O que fazer com uma resposta. */
    enum Verdict {
        DONE,
        /** Nada foi executado: repetir é seguro. */
        RETRY,
        /** A escrita pode ter acontecido: não se repete. */
        UNKNOWN,
        /** Recusa definitiva. */
        ERROR
    }

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(60);
    private static final long RETRY_PAUSE_MILLIS = 20;
    private static final long GIVE_UP_NANOS = Duration.ofSeconds(60).toNanos();

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final List<String> nodes;
    private final List<String> ready = new CopyOnWriteArrayList<>();
    private final AtomicLong retries = new AtomicLong();
    private final Thread balancer;
    private volatile boolean open = true;

    /** @param nodes bases HTTP, por exemplo {@code http://10.0.0.1:8000} */
    GateClient(List<String> nodes) {
        this.nodes = List.copyOf(nodes);
        this.balancer = Thread.ofPlatform().daemon(true).name("gate-balancer").start(this::watchReadiness);
    }

    private void watchReadiness() {
        while (open) {
            for (String node : nodes) {
                boolean up = isReady(node);
                if (up && !ready.contains(node)) {
                    ready.add(node);
                } else if (!up) {
                    ready.remove(node);
                }
            }
            pause(50);
        }
    }

    boolean isReady(String node) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(node + "/ready")).timeout(Duration.ofSeconds(1))
                .build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (IOException down) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    static Verdict classify(int status, String body) {
        if (status >= 200 && status < 300) {
            return Verdict.DONE;
        }
        // 307: o seguidor não executou; 503: sem líder, recusa do QoS, leitura defasada ou servidor cheio
        if (status == 307 || status == 503 || status == 429) {
            return Verdict.RETRY;
        }
        if (status == 504 || body.contains("OUTCOME_UNKNOWN")) {
            return Verdict.UNKNOWN;
        }
        return Verdict.ERROR;
    }

    /** O resultado de um pedido, já com as repetições. */
    record Reply(Verdict verdict, int status, String body) {
        AxonValue json() {
            return AxonJson.parseDocument(body);
        }
    }

    /** Executa AxonQL em algum nó pronto, repetindo enquanto for seguro. */
    Reply sql(String sql) {
        long deadline = System.nanoTime() + GIVE_UP_NANOS;
        String target = null;
        Reply last = new Reply(Verdict.ERROR, 0, "nenhum nó pronto");
        while (System.nanoTime() < deadline) {
            if (target == null) {
                if (ready.isEmpty()) {
                    pause(RETRY_PAUSE_MILLIS);
                    continue;
                }
                List<String> now = List.copyOf(ready);
                if (now.isEmpty()) {
                    continue;
                }
                target = now.get(ThreadLocalRandom.current().nextInt(now.size()));
            }
            last = send(target, sql, Map.of());
            if (last.verdict() != Verdict.RETRY) {
                return last;
            }
            retries.incrementAndGet();
            // o redirecionamento diz onde está o líder
            target = last.status() == 307 ? last.body() : null;
            pause(RETRY_PAUSE_MILLIS);
        }
        return new Reply(Verdict.ERROR, last.status(), "desistiu: " + last.body());
    }

    /** Um pedido a um nó, sem repetição. Num 307 o corpo é a base do nó para onde ir. */
    Reply send(String node, String sql, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(node + "/sql")).timeout(REQUEST_TIMEOUT)
            .header("Axon-Ns", "app").header("Axon-Db", "main");
        headers.forEach(request::header);
        try {
            HttpResponse<String> response = http.send(
                request.POST(HttpRequest.BodyPublishers.ofString(sql)).build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 307) {
                String location = response.headers().firstValue("Location").orElse("");
                int path = location.indexOf("/sql");
                return new Reply(path > 0 ? Verdict.RETRY : Verdict.ERROR, 307,
                    path > 0 ? location.substring(0, path) : location);
            }
            return new Reply(classify(response.statusCode(), response.body()), response.statusCode(), response.body());
        } catch (ConnectException | HttpConnectTimeoutException notSent) {
            return new Reply(Verdict.RETRY, 0, "sem conexão com " + node);
        } catch (IOException lost) {
            return new Reply(Verdict.UNKNOWN, 0, node + ": " + lost);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Reply(Verdict.ERROR, 0, "interrompido");
        }
    }

    HttpResponse<String> get(String node, String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(node + path)).timeout(REQUEST_TIMEOUT).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    HttpResponse<String> post(String node, String path) throws IOException, InterruptedException {
        return http.send(HttpRequest.newBuilder(URI.create(node + path)).timeout(Duration.ofMinutes(30))
            .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    long retries() {
        return retries.get();
    }

    static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrompido", interrupted);
        }
    }

    @Override
    public void close() {
        open = false;
        balancer.interrupt();
    }
}
