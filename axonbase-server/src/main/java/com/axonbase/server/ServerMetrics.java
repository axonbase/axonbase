package com.axonbase.server;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Contadores e histograma do servidor, compartilhados entre os transportes HTTP, RPC e WebSocket.
 * Tudo é lock-free; a renderização no formato Prometheus fica em {@link #render(StringBuilder)}.
 */
public final class ServerMetrics {

    /** Limite em segundos a partir do qual uma consulta conta como lenta. */
    public static final double SLOW_SECONDS = 1.0;

    /** Limite em uso (configurável por AXON_SLOW_QUERY_MS). */
    private volatile double slowSeconds = SLOW_SECONDS;

    public void slowSeconds(double seconds) {
        this.slowSeconds = seconds;
    }

    /** Buckets do histograma de consultas, de 1 ms a 30 s. */
    private static final double[] BUCKETS = {0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30};
    private static final String[] BUCKET_LABELS = {"0.001", "0.005", "0.01", "0.025", "0.05", "0.1", "0.25",
        "0.5", "1", "2.5", "5", "10", "30"};

    private final AtomicLong queries = new AtomicLong();
    private final AtomicLong slowQueries = new AtomicLong();
    private final AtomicLong queryErrors = new AtomicLong();
    private final AtomicLong signins = new AtomicLong();
    private final AtomicLong signinFailures = new AtomicLong();
    private final AtomicLong requests = new AtomicLong();
    private final Map<String, LongAdder> http = new ConcurrentHashMap<>();
    private final LongAdder[] bucketCounts = new LongAdder[BUCKETS.length + 1];
    private final LongAdder durationNanos = new LongAdder();

    public ServerMetrics() {
        for (int i = 0; i < bucketCounts.length; i++) {
            bucketCounts[i] = new LongAdder();
        }
    }

    /** Registra uma consulta concluída. */
    public void query(long nanos, boolean error) {
        queries.incrementAndGet();
        if (error) {
            queryErrors.incrementAndGet();
        }
        double seconds = nanos / 1_000_000_000.0;
        if (seconds >= slowSeconds) {
            slowQueries.incrementAndGet();
        }
        durationNanos.add(nanos);
        int slot = BUCKETS.length;
        for (int i = 0; i < BUCKETS.length; i++) {
            if (seconds <= BUCKETS[i]) {
                slot = i;
                break;
            }
        }
        bucketCounts[slot].increment();
    }

    public void signin(boolean ok) {
        (ok ? signins : signinFailures).incrementAndGet();
    }

    /** Registra uma requisição HTTP concluída. */
    public void http(String endpoint, int status) {
        requests.incrementAndGet();
        http.computeIfAbsent(endpoint + "\u0000" + status, k -> new LongAdder()).increment();
    }

    public long requests() {
        return requests.get();
    }

    public long queries() {
        return queries.get();
    }

    public long queryErrors() {
        return queryErrors.get();
    }

    public long signins() {
        return signins.get();
    }

    public long signinFailures() {
        return signinFailures.get();
    }

    /** Acrescenta as métricas de requisições e consultas ao corpo Prometheus. */
    public void render(StringBuilder sb) {
        sb.append("# TYPE axe_requests_total counter\n")
          .append("axe_requests_total ").append(requests.get()).append("\n")
          .append("# TYPE axe_http_requests_total counter\n");
        new TreeMap<>(http).forEach((k, v) -> {
            int sep = k.indexOf('\u0000');
            sb.append("axe_http_requests_total{endpoint=\"").append(label(k.substring(0, sep)))
              .append("\",status=\"").append(k.substring(sep + 1)).append("\"} ").append(v.sum()).append("\n");
        });
        sb.append("# TYPE axe_query_total counter\n")
          .append("axe_query_total ").append(queries.get()).append("\n")
          .append("# TYPE axe_query_slow_total counter\n")
          .append("axe_query_slow_total ").append(slowQueries.get()).append("\n")
          .append("# TYPE axe_query_error_total counter\n")
          .append("axe_query_error_total ").append(queryErrors.get()).append("\n")
          .append("# TYPE axe_auth_signin_total counter\n")
          .append("axe_auth_signin_total ").append(signins.get()).append("\n")
          .append("# TYPE axe_auth_signin_fail_total counter\n")
          .append("axe_auth_signin_fail_total ").append(signinFailures.get()).append("\n")
          .append("# TYPE axe_query_duration_seconds histogram\n");
        long cumulative = 0;
        for (int i = 0; i < BUCKETS.length; i++) {
            cumulative += bucketCounts[i].sum();
            sb.append("axe_query_duration_seconds_bucket{le=\"").append(BUCKET_LABELS[i]).append("\"} ")
              .append(cumulative).append("\n");
        }
        cumulative += bucketCounts[BUCKETS.length].sum();
        sb.append("axe_query_duration_seconds_bucket{le=\"+Inf\"} ").append(cumulative).append("\n")
          .append("axe_query_duration_seconds_sum ").append(durationNanos.sum() / 1_000_000_000.0).append("\n")
          .append("axe_query_duration_seconds_count ").append(cumulative).append("\n");
    }

    /** Escapa um valor de label Prometheus. */
    public static String label(String v) {
        return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }
}
