package com.axonbase.core.embedding;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.core.catalog.Catalog;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Cliente de embeddings: transforma texto em vetor chamando um provedor HTTP que fala o formato
 * {@code POST /v1/embeddings} (OpenAI, Ollama, llama.cpp, vLLM...). Não carrega modelo nenhum: o texto sai para a URL
 * configurada em {@code DEFINE EMBEDDING}, então quem quer privacidade aponta para um modelo próprio.
 *
 * <p>A chave nunca passa pelo catálogo: o embedding guarda só o NOME da variável de ambiente, lida aqui a cada
 * chamada. Há repetição com espera crescente em 429, 5xx e falhas de conexão (respeitando {@code Retry-After}),
 * limite de chamadas simultâneas, cache das consultas repetidas e, opcionalmente, uma lista de hosts permitidos
 * ({@code AXON_EMBEDDING_ALLOWED_HOSTS}) para que o SQL não vire porta de entrada para a rede interna.</p>
 */
public final class EmbeddingClient {

    /** Papel do texto: a consulta e o documento podem levar prefixos diferentes (modelos como nomic e e5). */
    public enum Role { QUERY, DOCUMENT }

    /** Contadores para métricas. */
    public record Stats(long requests, long errors, long retries, long cacheHits, long texts, long characters,
                        long nanos) {
    }

    public static final int MAX_ATTEMPTS = 4;
    static final long MAX_WAIT_MILLIS = 30_000;
    private static final int CACHE_SIZE = 1000;

    private final HttpClient http;
    private final Function<String, String> env;
    private final Semaphore slots;
    private final Sleeper sleeper;
    private final long baseWaitMillis;
    private final Map<String, double[]> cache = new LinkedHashMap<>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, double[]> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong cacheHits = new AtomicLong();
    private final AtomicLong texts = new AtomicLong();
    private final AtomicLong characters = new AtomicLong();
    private final AtomicLong nanos = new AtomicLong();

    /** Espera entre tentativas; os testes trocam por uma versão sem espera. */
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public EmbeddingClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), System::getenv,
            Thread::sleep, 500, intEnv(System.getenv("AXON_EMBEDDING_CONCURRENCY"), 4));
    }

    public EmbeddingClient(HttpClient http, Function<String, String> env, Sleeper sleeper, long baseWaitMillis,
                           int concurrency) {
        this.http = http;
        this.env = env;
        this.sleeper = sleeper;
        this.baseWaitMillis = baseWaitMillis;
        this.slots = new Semaphore(Math.max(1, concurrency), true);
    }

    private static int intEnv(String v, int fallback) {
        try {
            return v == null || v.isBlank() ? fallback : Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public Stats stats() {
        return new Stats(requests.get(), errors.get(), retries.get(), cacheHits.get(), texts.get(), characters.get(),
            nanos.get());
    }

    // ------------------------------------------------------------------ validação da definição

    /**
     * Confere a URL do embedding: http ou https e, se {@code AXON_EMBEDDING_ALLOWED_HOSTS} existir, host na lista.
     * Chamado ao definir o embedding e a cada uso (a lista pode mudar entre reinícios).
     */
    public void checkUrl(String url) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw AxonError.internal(Messages.get("embedding_url_invalid", url));
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ((!scheme.equals("http") && !scheme.equals("https")) || uri.getHost() == null) {
            throw AxonError.internal(Messages.get("embedding_url_invalid", url));
        }
        String allowed = env.apply("AXON_EMBEDDING_ALLOWED_HOSTS");
        if (allowed != null && !allowed.isBlank()) {
            Set<String> hosts = java.util.Arrays.stream(allowed.split(",")).map(String::trim)
                .filter(h -> !h.isEmpty()).map(h -> h.toLowerCase(Locale.ROOT)).collect(Collectors.toSet());
            if (!hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
                throw AxonError.internal(Messages.get("embedding_host_not_allowed", uri.getHost()));
            }
        }
    }

    // ------------------------------------------------------------------ chamada

    /** Vetores dos textos, na ordem. Os textos vão em lotes de {@code def.batch()}. */
    public List<double[]> embed(Catalog.EmbeddingDef def, List<String> input, Role role) {
        checkUrl(def.url());
        String prefix = role == Role.QUERY ? def.queryPrefix() : def.documentPrefix();
        List<double[]> out = new ArrayList<>(input.size());
        List<Integer> missing = new ArrayList<>();
        List<String> texts = new ArrayList<>(input.size());
        for (int i = 0; i < input.size(); i++) {
            String text = (prefix == null ? "" : prefix) + input.get(i);
            texts.add(text);
            double[] hit = role == Role.QUERY ? cached(def, text) : null;
            out.add(hit);
            if (hit == null) {
                missing.add(i);
            } else {
                cacheHits.incrementAndGet();
            }
        }
        int batch = Math.max(1, def.batch());
        for (int from = 0; from < missing.size(); from += batch) {
            List<Integer> chunk = missing.subList(from, Math.min(missing.size(), from + batch));
            List<String> body = new ArrayList<>(chunk.size());
            chunk.forEach(i -> body.add(texts.get(i)));
            List<double[]> vectors = request(def, body);
            for (int k = 0; k < chunk.size(); k++) {
                out.set(chunk.get(k), vectors.get(k));
                if (role == Role.QUERY) {
                    remember(def, texts.get(chunk.get(k)), vectors.get(k));
                }
            }
        }
        return out;
    }

    public double[] embedOne(Catalog.EmbeddingDef def, String text, Role role) {
        return embed(def, List.of(text), role).get(0);
    }

    // ------------------------------------------------------------------ cache

    private static String cacheKey(Catalog.EmbeddingDef def, String text) {
        return def.url() + '\u0000' + def.model() + '\u0000' + def.dimension() + '\u0000' + text;
    }

    private double[] cached(Catalog.EmbeddingDef def, String text) {
        synchronized (cache) {
            return cache.get(cacheKey(def, text));
        }
    }

    private void remember(Catalog.EmbeddingDef def, String text, double[] vector) {
        synchronized (cache) {
            cache.put(cacheKey(def, text), vector);
        }
    }

    // ------------------------------------------------------------------ HTTP

    private List<double[]> request(Catalog.EmbeddingDef def, List<String> input) {
        String key = null;
        if (def.keyEnv() != null) {
            key = env.apply(def.keyEnv());
            if (key == null || key.isBlank()) {
                throw AxonError.internal(Messages.get("embedding_key_missing", def.keyEnv(), def.name()));
            }
        }
        Map<String, AxonValue> body = new LinkedHashMap<>();
        body.put("model", AxonValue.str(def.model()));
        List<AxonValue> items = new ArrayList<>(input.size());
        input.forEach(t -> items.add(AxonValue.str(t)));
        body.put("input", AxonValue.array(items));
        body.put("encoding_format", AxonValue.str("float"));
        if (def.sendDimension()) {
            body.put("dimensions", AxonValue.num(def.dimension()));
        }
        String json = AxonJson.write(AxonValue.object(body));
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(def.url()))
            .timeout(Duration.ofSeconds(Math.max(1, def.timeoutSeconds())))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json));
        if (key != null) {
            if (def.authHeader() == null) {
                req.header("Authorization", "Bearer " + key);
            } else {
                req.header(def.authHeader(), key);
            }
        }
        HttpRequest request = req.build();
        long started = System.nanoTime();
        try {
            slots.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AxonError.internal(Messages.get("embedding_unreachable", def.url(), "interrompido"));
        }
        try {
            requests.incrementAndGet();
            texts.addAndGet(input.size());
            characters.addAndGet(input.stream().mapToLong(String::length).sum());
            String failure = null;
            for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
                long wait = baseWaitMillis << (attempt - 1);
                try {
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    int status = response.statusCode();
                    if (status >= 200 && status < 300) {
                        List<double[]> vectors = parse(def, response.body(), input.size());
                        nanos.addAndGet(System.nanoTime() - started);
                        return vectors;
                    }
                    failure = Messages.get("embedding_http_error", status, snippet(response.body()));
                    if (status != 429 && status < 500) {
                        break;
                    }
                    wait = retryAfterMillis(response).orElse(wait);
                } catch (IOException e) {
                    failure = Messages.get("embedding_unreachable", def.url(), String.valueOf(e.getMessage()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    failure = Messages.get("embedding_unreachable", def.url(), "interrompido");
                    break;
                }
                if (attempt < MAX_ATTEMPTS) {
                    retries.incrementAndGet();
                    try {
                        sleeper.sleep(Math.min(wait, MAX_WAIT_MILLIS));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            errors.incrementAndGet();
            nanos.addAndGet(System.nanoTime() - started);
            throw AxonError.internal(failure == null ? Messages.get("embedding_bad_response", "sem resposta") : failure);
        } finally {
            slots.release();
        }
    }

    private static java.util.Optional<Long> retryAfterMillis(HttpResponse<String> response) {
        return response.headers().firstValue("Retry-After").map(v -> {
            try {
                return Long.parseLong(v.trim()) * 1000;
            } catch (NumberFormatException e) {
                return null;
            }
        });
    }

    private static String snippet(String body) {
        if (body == null) {
            return "";
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 300 ? flat.substring(0, 300) + "..." : flat;
    }

    /** {@code {"data":[{"index":0,"embedding":[...]}, ...]}}; devolve na ordem de {@code index}. */
    private List<double[]> parse(Catalog.EmbeddingDef def, String body, int expected) {
        AxonValue root;
        try {
            root = AxonJson.parseDocument(body);
        } catch (RuntimeException e) {
            throw AxonError.internal(Messages.get("embedding_bad_response", snippet(body)));
        }
        if (!root.isObject() || !root.asObject().containsKey("data") || !root.asObject().get("data").isArray()) {
            throw AxonError.internal(Messages.get("embedding_bad_response", snippet(body)));
        }
        List<AxonValue> data = root.asObject().get("data").asArray();
        if (data.size() != expected) {
            throw AxonError.internal(Messages.get("embedding_bad_response",
                "esperava " + expected + " vetores e vieram " + data.size()));
        }
        double[][] ordered = new double[expected][];
        for (int i = 0; i < data.size(); i++) {
            AxonValue item = data.get(i);
            if (!item.isObject() || !item.asObject().containsKey("embedding") || !item.asObject().get("embedding").isArray()) {
                throw AxonError.internal(Messages.get("embedding_bad_response", snippet(body)));
            }
            int index = i;
            AxonValue idx = item.asObject().get("index");
            if (idx != null && idx.isNumber() && idx.asLong() >= 0 && idx.asLong() < expected) {
                index = (int) idx.asLong();
            }
            List<AxonValue> raw = item.asObject().get("embedding").asArray();
            double[] vector = new double[raw.size()];
            for (int k = 0; k < raw.size(); k++) {
                if (!raw.get(k).isNumber()) {
                    throw AxonError.internal(Messages.get("embedding_bad_response", "vetor com valor não numérico"));
                }
                vector[k] = raw.get(k).asDouble();
            }
            if (vector.length != def.dimension()) {
                throw AxonError.internal(Messages.get("embedding_dimension_mismatch", def.name(), vector.length,
                    def.dimension()));
            }
            ordered[index] = vector;
        }
        List<double[]> out = new ArrayList<>(expected);
        for (double[] v : ordered) {
            if (v == null) {
                throw AxonError.internal(Messages.get("embedding_bad_response", "índices repetidos na resposta"));
            }
            out.add(v);
        }
        return out;
    }

    /** O vetor como valor do banco (lista de números). */
    public static AxonValue toValue(double[] vector) {
        List<AxonValue> items = new ArrayList<>(vector.length);
        for (double d : vector) {
            items.add(AxonValue.num(d));
        }
        return AxonValue.array(items);
    }
}
