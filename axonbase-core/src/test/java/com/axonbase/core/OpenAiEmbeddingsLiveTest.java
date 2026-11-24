package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Teste "ao vivo" contra a API da OpenAI. Só roda com {@code -Daxon.live=true} e {@code OPENAI_API_KEY} no ambiente;
 * faz poucas chamadas (custo de centavos de milésimo). Confirma o contrato real do {@code /v1/embeddings}.
 */
@EnabledIfSystemProperty(named = "axon.live", matches = "true")
@EnabledIfEnvironmentVariable(named = "OPENAI_API_KEY", matches = ".+")
@DisplayName("Embeddings ao vivo (OpenAI)")
class OpenAiEmbeddingsLiveTest {

    private Datastore ds;
    private Session session;

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    private static List<String> titles(AxonValue rows) {
        List<String> out = new ArrayList<>();
        rows.asArray().forEach(r -> out.add(r.asObject().get("titulo").asString()));
        return out;
    }

    @Test
    @DisplayName("text-embedding-3-small: dimensão, vetor automático, busca por texto em português e SEND DIMENSION")
    void liveRoundTrip() {
        ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        session = Session.create();
        session.namespace("test");
        session.database("dev");

        run("DEFINE EMBEDDING default PROVIDER http URL \"https://api.openai.com/v1/embeddings\" "
            + "MODEL \"text-embedding-3-small\" DIMENSION 1536 KEY ENV \"OPENAI_API_KEY\" TIMEOUT 30");
        assertEquals(1536, run("RETURN vector::embed('motorista por perto')").asArray().size());

        run("DEFINE TABLE artigo SCHEMAFULL");
        run("DEFINE FIELD titulo ON TABLE artigo TYPE string");
        run("DEFINE FIELD corpo ON TABLE artigo TYPE string");
        run("DEFINE FIELD emb ON TABLE artigo TYPE vector<1536> EMBEDDING default FROM corpo");
        run("CREATE INDEX artigo_vetor ON TABLE artigo COLUMNS emb HNSW DIMENSION 1536 DIST cosine");
        run("CREATE artigo:1 CONTENT {titulo: 'Motoristas', corpo: 'Como achar os motoristas num raio de dois quilômetros e mostrar no mapa'}");
        run("CREATE artigo:2 CONTENT {titulo: 'Grafos', corpo: 'Relações entre pessoas, quem segue quem e o caminho entre duas pessoas'}");
        run("CREATE artigo:3 CONTENT {titulo: 'Colunas', corpo: 'Somas e médias lidas direto do índice columnar'}");
        run("CREATE artigo:4 CONTENT {titulo: 'Bolo', corpo: 'Receita de bolo de chocolate com cobertura'}");

        assertEquals("Motoristas", titles(run("SELECT titulo FROM artigo WHERE emb @@ 'motorista por perto' LIMIT 1")).get(0));
        assertEquals("Grafos", titles(run("SELECT titulo FROM artigo WHERE emb @@ 'rede de amigos' LIMIT 1")).get(0));
        assertEquals("Colunas", titles(run("SELECT titulo FROM artigo WHERE emb @@ 'somar e calcular a média dos números de uma coluna' LIMIT 1")).get(0));

        // vetores menores pelo parâmetro dimensions
        run("DEFINE EMBEDDING curto PROVIDER http URL \"https://api.openai.com/v1/embeddings\" "
            + "MODEL \"text-embedding-3-small\" DIMENSION 256 SEND DIMENSION KEY ENV \"OPENAI_API_KEY\"");
        assertEquals(256, run("RETURN vector::embed('motorista', 'curto')").asArray().size());
    }
}
