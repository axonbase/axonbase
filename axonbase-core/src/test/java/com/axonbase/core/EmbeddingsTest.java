package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.embedding.EmbeddingClient;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Embeddings: provedor HTTP, vetor automático no campo, busca por texto")
class EmbeddingsTest {

    private static final String[] VOCAB = {"motorista", "grafo", "coluna", "mapa"};
    private static final String KEY = "chave-de-teste";

    private HttpServer server;
    private String url;
    private final List<AxonValue> requests = new CopyOnWriteArrayList<>();
    private final List<String> authHeaders = new CopyOnWriteArrayList<>();
    private final AtomicInteger failFirst = new AtomicInteger(0);
    private volatile int failStatus = 429;
    private volatile int forcedDimension = 0;

    private Datastore ds;
    private Session session;
    private Map<String, String> env;

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            authHeaders.add(String.valueOf(exchange.getRequestHeaders().getFirst("Authorization")));
            AxonValue req = AxonJson.parseDocument(body);
            requests.add(req);
            if (failFirst.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                exchange.getResponseHeaders().add("Retry-After", "0");
                exchange.sendResponseHeaders(failStatus, -1);
                exchange.close();
                return;
            }
            StringBuilder out = new StringBuilder("{\"data\":[");
            List<AxonValue> input = req.asObject().get("input").asArray();
            for (int i = 0; i < input.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append("{\"index\":").append(i).append(",\"embedding\":").append(vectorOf(input.get(i).asString())).append('}');
            }
            out.append("],\"usage\":{\"prompt_tokens\":1,\"total_tokens\":1}}");
            byte[] bytes = out.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        url = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/embeddings";

        env = new java.util.HashMap<>(Map.of("EMB_KEY", KEY));
        ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        ds.useEmbeddingClient(client());
        session = Session.create();
        session.namespace("test");
        session.database("dev");
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    private EmbeddingClient client() {
        return new EmbeddingClient(HttpClient.newHttpClient(), env::get, millis -> { }, 0, 4);
    }

    /** Vetor de 4 dimensões: uma por palavra do vocabulário, mais um pequeno piso para nunca ser nulo. */
    private String vectorOf(String text) {
        String t = text.toLowerCase();
        int dim = forcedDimension > 0 ? forcedDimension : VOCAB.length;
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < dim; i++) {
            if (i > 0) {
                sb.append(',');
            }
            boolean hit = i < VOCAB.length && t.contains(VOCAB[i]);
            sb.append(hit ? "1.0" : "0.01");
        }
        return sb.append(']').toString();
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    private void defineDefault() {
        run("DEFINE EMBEDDING default PROVIDER http URL \"" + url + "\" MODEL \"fake-4\" DIMENSION 4 KEY ENV \"EMB_KEY\"");
    }

    private void articles() {
        defineDefault();
        run("DEFINE TABLE artigo SCHEMAFULL");
        run("DEFINE FIELD titulo ON TABLE artigo TYPE string");
        run("DEFINE FIELD corpo ON TABLE artigo TYPE string");
        run("DEFINE FIELD emb ON TABLE artigo TYPE vector<4> EMBEDDING default FROM corpo");
        run("CREATE INDEX artigo_vetor ON TABLE artigo COLUMNS emb HNSW DIMENSION 4 DIST cosine");
        run("CREATE artigo:1 CONTENT {titulo: 'Motoristas', corpo: 'achar o motorista mais perto no mapa'}");
        run("CREATE artigo:2 CONTENT {titulo: 'Grafos', corpo: 'relacoes de um grafo de pessoas'}");
        run("CREATE artigo:3 CONTENT {titulo: 'Colunas', corpo: 'agregacao pela coluna do indice'}");
    }

    private static List<String> titles(AxonValue rows) {
        List<String> out = new ArrayList<>();
        rows.asArray().forEach(r -> out.add(r.asObject().get("titulo").asString()));
        return out;
    }

    // ------------------------------------------------------------- definição

    @Test
    @DisplayName("DEFINE EMBEDDING aparece no INFO sem a chave e sobrevive ao reinício")
    void defineInfoAndReplay() {
        MemoryBackend backend = new MemoryBackend();
        Datastore first = new Datastore(backend);
        first.useEmbeddingClient(client());
        first.createDatabase("test", "dev");
        first.execute("DEFINE EMBEDDING default PROVIDER http URL \"" + url + "\" MODEL \"fake-4\" DIMENSION 4 "
            + "KEY ENV \"EMB_KEY\" TIMEOUT 15 BATCH 8 DOCUMENT PREFIX \"doc: \" QUERY PREFIX \"q: \" SEND DIMENSION; "
            + "DEFINE TABLE artigo SCHEMAFULL; DEFINE FIELD corpo ON TABLE artigo TYPE string; "
            + "DEFINE FIELD emb ON TABLE artigo TYPE vector<4> EMBEDDING default FROM corpo", session, null);

        Datastore second = new Datastore(backend);
        second.useEmbeddingClient(client());
        var info = second.execute("INFO FOR DATABASE", session, null).asObject();
        var e = info.get("embeddings").asObject().get("default").asObject();
        assertEquals("http", e.get("provider").asString());
        assertEquals("fake-4", e.get("model").asString());
        assertEquals(4, e.get("dimension").asLong());
        assertEquals("EMB_KEY", e.get("key_env").asString());
        assertEquals(15, e.get("timeout").asLong());
        assertEquals(8, e.get("batch").asLong());
        assertTrue(e.get("send_dimension").asBool());
        assertEquals("doc: ", e.get("document_prefix").asString());
        assertFalse(info.toString().contains(KEY), "a chave nunca aparece");
        var field = second.execute("INFO FOR TABLE artigo", session, null).asObject().get("fields").asObject()
            .get("emb").asObject().get("embedding").asObject();
        assertEquals("default", field.get("name").asString());
        assertEquals("corpo", field.get("from").asString());
    }

    @Test
    @DisplayName("DROP EMBEDDING recusa se um campo usa e, depois de solto, não volta no reinício")
    void dropEmbedding() {
        MemoryBackend backend = new MemoryBackend();
        Datastore store = new Datastore(backend);
        store.useEmbeddingClient(client());
        store.createDatabase("test", "dev");
        store.execute("DEFINE EMBEDDING default PROVIDER http URL \"" + url + "\" MODEL \"m\" DIMENSION 4; "
            + "DEFINE TABLE artigo SCHEMAFULL; DEFINE FIELD corpo ON TABLE artigo TYPE string; "
            + "DEFINE FIELD emb ON TABLE artigo TYPE vector<4> EMBEDDING default FROM corpo", session, null);
        AxonError e = assertThrows(AxonError.class, () -> store.execute("DROP EMBEDDING default", session, null));
        assertTrue(e.getMessage().contains("artigo.emb"), e.getMessage());

        store.execute("DEFINE EMBEDDING outro PROVIDER http URL \"" + url + "\" MODEL \"m\" DIMENSION 4", session, null);
        store.execute("DROP EMBEDDING outro", session, null);
        Datastore again = new Datastore(backend);
        var embeddings = again.execute("INFO FOR DATABASE", session, null).asObject().get("embeddings").asObject();
        assertTrue(embeddings.containsKey("default"));
        assertFalse(embeddings.containsKey("outro"));
    }

    @Test
    @DisplayName("tipo do campo, dimensão do índice e provedor são conferidos na definição")
    void definitionChecks() {
        defineDefault();
        run("DEFINE TABLE t SCHEMAFULL");
        assertThrows(AxonError.class, () -> run("DEFINE FIELD v ON TABLE t TYPE vector<3> EMBEDDING default FROM x"));
        assertThrows(AxonError.class, () -> run("DEFINE FIELD v ON TABLE t TYPE vector<4> EMBEDDING nada FROM x"));
        run("CREATE INDEX t_v ON TABLE t COLUMNS v HNSW DIMENSION 3 DIST cosine");
        assertThrows(AxonError.class, () -> run("DEFINE FIELD v ON TABLE t TYPE vector<4> EMBEDDING default FROM x"));
        assertThrows(RuntimeException.class, () -> run("DEFINE EMBEDDING x PROVIDER builtin URL \"" + url
            + "\" MODEL \"m\" DIMENSION 4"));
        assertThrows(RuntimeException.class, () -> run("DEFINE EMBEDDING x PROVIDER http URL \"ftp://a/b\" MODEL \"m\" DIMENSION 4"));
    }

    // -------------------------------------------------------------- função

    @Test
    @DisplayName("vector::embed devolve o vetor, aceita o nome do embedding e o apelido fromstr")
    void embedFunction() {
        defineDefault();
        AxonValue v = run("RETURN vector::embed('onde esta o motorista')");
        assertEquals(4, v.asArray().size());
        assertEquals(1.0, v.asArray().get(0).asDouble(), 1e-9);
        assertEquals(4, run("RETURN vector::fromstr('um grafo')").asArray().size());
        assertTrue(run("RETURN vector::embed(null)").isNull());
        assertThrows(AxonError.class, () -> run("RETURN vector::embed('x', 'inexistente')"));
        assertEquals("Bearer " + KEY, authHeaders.get(0));
        assertEquals("float", requests.get(0).asObject().get("encoding_format").asString());
        assertFalse(requests.get(0).asObject().containsKey("dimensions"));
    }

    @Test
    @DisplayName("consultas repetidas usam o cache: só um pedido ao provedor")
    void queryCache() {
        defineDefault();
        run("RETURN vector::embed('motorista')");
        run("RETURN vector::embed('motorista')");
        assertEquals(1, requests.size());
        assertEquals(1, ds.embeddings().stats().cacheHits());
    }

    @Test
    @DisplayName("SEND DIMENSION manda dimensions; prefixos entram no texto enviado")
    void sendDimensionAndPrefixes() {
        run("DEFINE EMBEDDING default PROVIDER http URL \"" + url + "\" MODEL \"m\" DIMENSION 4 SEND DIMENSION "
            + "DOCUMENT PREFIX \"doc: \" QUERY PREFIX \"q: \"");
        run("RETURN vector::embed('motorista')");
        assertEquals(4, requests.get(0).asObject().get("dimensions").asLong());
        assertEquals("q: motorista", requests.get(0).asObject().get("input").asArray().get(0).asString());
        run("DEFINE TABLE t SCHEMAFULL; DEFINE FIELD c ON TABLE t TYPE string; "
            + "DEFINE FIELD v ON TABLE t TYPE vector<4> EMBEDDING default FROM c; CREATE t:1 CONTENT {c: 'grafo'}");
        assertEquals("doc: grafo", requests.get(1).asObject().get("input").asArray().get(0).asString());
    }

    // ---------------------------------------------------------- vetor no campo

    @Test
    @DisplayName("CREATE e UPDATE preenchem o vetor sozinhos; só recalcula quando o texto muda")
    void autoEmbedOnWrite() {
        articles();
        int afterCreates = requests.size();
        assertEquals(3, afterCreates);
        AxonValue a = run("SELECT emb FROM artigo:1").asObject().get("emb");
        assertEquals(4, a.asArray().size());
        assertEquals(1.0, a.asArray().get(0).asDouble(), 1e-9);

        run("UPDATE artigo:1 SET titulo = 'Outro titulo'");
        assertEquals(afterCreates, requests.size(), "o texto de origem não mudou");
        run("UPDATE artigo:1 SET corpo = 'agora so grafo'");
        assertEquals(afterCreates + 1, requests.size());
        assertEquals(1.0, run("SELECT emb FROM artigo:1").asObject().get("emb").asArray().get(1).asDouble(), 1e-9);
    }

    @Test
    @DisplayName("vetor informado na mão é mantido; dimensão errada e origem sem texto são tratadas")
    void manualVectorAndEdgeCases() {
        articles();
        int before = requests.size();
        run("CREATE artigo:9 CONTENT {titulo: 'Manual', corpo: 'texto', emb: [0.5, 0.5, 0.5, 0.5]}");
        assertEquals(before, requests.size());
        assertEquals(0.5, run("SELECT emb FROM artigo:9").asObject().get("emb").asArray().get(0).asDouble(), 1e-9);
        assertThrows(AxonError.class, () -> run("CREATE artigo:10 CONTENT {titulo: 'x', corpo: 'y', emb: [1, 2]}"));
        run("CREATE artigo:11 CONTENT {titulo: 'Sem corpo'}");
        AxonValue noVector = run("SELECT * FROM artigo:11").asObject().get("emb");
        assertTrue(noVector == null || noVector.isNull(), "sem texto de origem não há vetor");
        assertEquals(before, requests.size());
    }

    @Test
    @DisplayName("provedor fora do ar derruba a escrita e nada é gravado")
    void providerDownFailsTheWrite() {
        run("DEFINE EMBEDDING lento PROVIDER http URL \"http://127.0.0.1:1/v1/embeddings\" MODEL \"m\" DIMENSION 4 TIMEOUT 2");
        run("DEFINE TABLE t SCHEMAFULL; DEFINE FIELD c ON TABLE t TYPE string; "
            + "DEFINE FIELD v ON TABLE t TYPE vector<4> EMBEDDING lento FROM c");
        AxonError e = assertThrows(AxonError.class, () -> run("CREATE t:1 CONTENT {c: 'texto'}"));
        assertTrue(e.getMessage().contains("inacess") || e.getMessage().contains("unreachable"), e.getMessage());
        assertEquals(0, run("SELECT * FROM t").asArray().size());
    }

    // ------------------------------------------------------------ resiliência

    @Test
    @DisplayName("429 e 5xx são repetidos; 4xx não; o erro do provedor chega ao usuário")
    void retriesAndErrors() {
        defineDefault();
        failFirst.set(2);
        assertEquals(4, run("RETURN vector::embed('motorista')").asArray().size());
        assertEquals(3, requests.size());
        assertEquals(2, ds.embeddings().stats().retries());

        requests.clear();
        failFirst.set(100);
        failStatus = 400;
        AxonError bad = assertThrows(AxonError.class, () -> run("RETURN vector::embed('grafo')"));
        assertTrue(bad.getMessage().contains("400"), bad.getMessage());
        assertEquals(1, requests.size(), "erro 4xx (que não é 429) não é repetido");

        requests.clear();
        failStatus = 503;
        AxonError down = assertThrows(AxonError.class, () -> run("RETURN vector::embed('mapa')"));
        assertTrue(down.getMessage().contains("503"), down.getMessage());
        assertEquals(EmbeddingClient.MAX_ATTEMPTS, requests.size());
        assertTrue(ds.embeddings().stats().errors() >= 2);
    }

    @Test
    @DisplayName("dimensão diferente da declarada é erro claro")
    void dimensionMismatch() {
        defineDefault();
        forcedDimension = 6;
        AxonError e = assertThrows(AxonError.class, () -> run("RETURN vector::embed('motorista')"));
        assertTrue(e.getMessage().contains("6") && e.getMessage().contains("4"), e.getMessage());
    }

    @Test
    @DisplayName("chave ausente no ambiente e host fora da lista permitida")
    void keyAndAllowList() {
        defineDefault();
        env.remove("EMB_KEY");
        AxonError e = assertThrows(AxonError.class, () -> run("RETURN vector::embed('motorista')"));
        assertTrue(e.getMessage().contains("EMB_KEY"), e.getMessage());
        assertFalse(e.getMessage().contains(KEY));

        env.put("AXON_EMBEDDING_ALLOWED_HOSTS", "api.openai.com, ollama.interno");
        AxonError host = assertThrows(AxonError.class, () -> run("DEFINE EMBEDDING x PROVIDER http URL \"" + url
            + "\" MODEL \"m\" DIMENSION 4"));
        assertTrue(host.getMessage().contains("127.0.0.1"), host.getMessage());
        env.put("AXON_EMBEDDING_ALLOWED_HOSTS", "127.0.0.1");
        run("DEFINE EMBEDDING x PROVIDER http URL \"" + url + "\" MODEL \"m\" DIMENSION 4");
    }

    // ------------------------------------------------------ busca por texto

    @Test
    @DisplayName("campo @@ 'texto' usa o embedding do campo e traz os mais próximos primeiro")
    void searchByText() {
        articles();
        AxonValue rows = run("SELECT titulo FROM artigo WHERE emb @@ 'onde esta o motorista'");
        assertEquals("Motoristas", titles(rows).get(0));
        assertEquals(3, rows.asArray().size());
        assertEquals(List.of("Colunas"), titles(run("SELECT titulo FROM artigo WHERE emb @@ 'coluna' LIMIT 1")));
        assertEquals(List.of("Grafos"), titles(run("SELECT titulo FROM artigo WHERE emb @@ 'um grafo' LIMIT 1")));
        // a consulta de busca usa o prefixo/papel de consulta (QUERY), não o de documento
        assertEquals("VECTOR", run("EXPLAIN SELECT titulo FROM artigo WHERE emb @@ 'um grafo' LIMIT 1")
            .asObject().get("strategy").asString());
    }

    @Test
    @DisplayName("busca híbrida: texto exato e significado na mesma consulta")
    void hybridWithText() {
        articles();
        run("DEFINE ANALYZER pt LOWERCASE");
        run("CREATE INDEX artigo_texto ON TABLE artigo COLUMNS corpo SEARCH ANALYZER pt");
        AxonValue rows = run("SELECT titulo, search::score() AS score FROM artigo "
            + "WHERE corpo @@ 'motorista' AND emb @@ 'onde esta o motorista' ORDER BY search::score() DESC");
        assertEquals(List.of("Motoristas"), titles(rows));
    }

    @Test
    @DisplayName("embedding usado com ORDER BY e LIMIT explícitos")
    void explicitOrderAndLimit() {
        articles();
        AxonValue rows = run("LET $q = vector::embed('um grafo'); SELECT titulo FROM artigo WHERE emb @@ $q "
            + "ORDER BY vector::distance::cosine(emb, $q) ASC LIMIT 2");
        assertEquals(2, rows.asArray().size());
        assertEquals("Grafos", titles(rows).get(0));
    }

    // ---------------------------------------------------------------- REEMBED

    @Test
    @DisplayName("REEMBED DRY RUN estima sem chamar o provedor; REEMBED regera os vetores")
    void reembed() {
        articles();
        int before = requests.size();
        AxonValue dry = run("REEMBED TABLE artigo DRY RUN");
        assertEquals(before, requests.size());
        assertEquals(3, dry.asObject().get("rows").asLong());
        long chars = dry.asObject().get("characters").asLong();
        assertTrue(chars > 0);
        assertEquals((chars + 3) / 4, dry.asObject().get("estimatedTokens").asLong());

        run("UPDATE artigo:1 SET emb = [0.1, 0.1, 0.1, 0.1]");
        AxonValue done = run("REEMBED TABLE artigo FIELD emb");
        assertEquals(3, done.asObject().get("updated").asLong());
        assertEquals(1.0, run("SELECT emb FROM artigo:1").asObject().get("emb").asArray().get(0).asDouble(), 1e-9);
        assertThrows(AxonError.class, () -> run("REEMBED TABLE artigo FIELD titulo"));
    }
}
