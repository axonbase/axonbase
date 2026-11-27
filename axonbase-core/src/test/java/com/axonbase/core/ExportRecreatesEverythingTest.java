package com.axonbase.core;

import com.axonbase.core.catalog.Catalog;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** O dump lógico leva o que o banco tem: arestas, chave-valor, embeddings, eventos e definições de campo. */
@DisplayName("Exportar e importar recria o banco")
class ExportRecreatesEverythingTest {

    private final Datastore source = new Datastore(new MemoryBackend());

    /** As funções de chave-valor não criam o banco: a exportação precisa que ele exista. */
    @BeforeEach
    void createDatabase() {
        source.createDatabase("n", "d");
    }

    private static AxonValue run(Datastore ds, String sql) {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return ds.execute(sql, s, null);
    }

    private Datastore copy() {
        Datastore target = new Datastore(new MemoryBackend());
        String dump = source.exportDatabase("n", "d");
        assertDoesNotThrow(() -> target.importDatabase("n", "d", dump), dump);
        return target;
    }

    private static String json(Datastore ds, String sql) {
        return AxonJson.write(run(ds, sql));
    }

    private static Catalog catalog(Datastore ds) {
        return ds.findDatabase("n", "d").catalog();
    }

    @Test
    @DisplayName("as arestas do RELATE voltam nos dois sentidos")
    void edgesSurvive() {
        run(source, "CREATE c:ana; CREATE pr:cafe; CREATE pr:1; CREATE c:\"joao silva\"");
        run(source, "RELATE c:ana->comprou->pr:cafe; RELATE c:ana->comprou->pr:1; "
            + "RELATE c:\"joao silva\"->segue->c:ana");

        Datastore target = copy();

        for (String query : new String[] {"SELECT ->comprou->pr AS p FROM c:ana",
            "SELECT <-comprou<-c AS q FROM pr:cafe", "SELECT ->segue->c AS s FROM c:\"joao silva\""}) {
            assertEquals(json(source, query), json(target, query), query);
        }
        assertEquals(json(source, "RETURN graph::connected(c:ana, pr:cafe)"),
            json(target, "RETURN graph::connected(c:ana, pr:cafe)"));
    }

    @Test
    @DisplayName("as propriedades gravadas pelo RELATE SET voltam com o tipo")
    void edgePropertiesSurvive() {
        run(source, "CREATE c:ana; CREATE pr:cafe");
        run(source, "RELATE c:ana->comprou->pr:cafe SET desde = 2020, quando = d\"2024-01-01T00:00:00Z\", "
            + "nota = 'um; dois'");

        Datastore target = copy();

        String query = "SELECT ->comprou.desde AS desde, ->comprou.quando AS quando, ->comprou.nota AS nota "
            + "FROM c:ana";
        assertTrue(json(source, query).contains("2020"), json(source, query));
        assertEquals(json(source, query), json(target, query));
    }

    @Test
    @DisplayName("as entradas do chave-valor voltam, com tipo e prazo")
    void keyValueSurvives() {
        run(source, "RETURN kv::set('tema', {cor: 'azul', quando: d\"2024-01-01T00:00:00Z\", saldo: -1.50dec})");
        run(source, "RETURN kv::set('sessao:\"1\"', [1, 2, 3], 3600)");

        Datastore target = copy();

        assertEquals(json(source, "RETURN kv::get('tema')"), json(target, "RETURN kv::get('tema')"));
        assertEquals("[1,2,3]", json(target, "RETURN kv::get('sessao:\"1\"')"));
        AxonValue session = run(target, "RETURN kv::scan('sessao')").asArray().get(0);
        long ttl = session.asObject().get("ttl").asLong();
        assertTrue(ttl > 3500 && ttl <= 3600, "prazo " + ttl);
    }

    @Test
    @DisplayName("um banco só com chave-valor também volta")
    void onlyKeyValueSurvives() {
        run(source, "RETURN kv::set('unico', 1)");

        assertEquals("1", json(copy(), "RETURN kv::get('unico')"));
    }

    @Test
    @DisplayName("os eventos voltam e não disparam de novo durante a importação")
    void eventsSurviveWithoutFiring() {
        run(source, "DEFINE TABLE pedido SCHEMALESS");
        run(source, "DEFINE EVENT registra ON TABLE pedido WHEN true THEN (CREATE trilha CONTENT {ok: true})");
        run(source, "CREATE pedido:1");

        Datastore target = copy();

        assertEquals(1, run(target, "SELECT * FROM trilha").asArray().size());
        run(target, "CREATE pedido:2");
        assertEquals(2, run(target, "SELECT * FROM trilha").asArray().size());
    }

    @Test
    @DisplayName("REFERENCES para uma tabela que vem depois no dump não impede a importação")
    void referencesImportInAnyOrder() {
        run(source, "CREATE z:1; DEFINE TABLE a SCHEMALESS; DEFINE FIELD r ON TABLE a REFERENCES z; "
            + "CREATE a:1 SET r = z:1");

        Datastore target = copy();

        assertEquals("z", catalog(target).table("a").fields().get("r").references());
        assertEquals(1, run(target, "SELECT * FROM a").asArray().size());
    }

    @Test
    @DisplayName("a importação não preenche DEFAULT em linha que não tinha o campo")
    void importDoesNotApplyDefaults() {
        run(source, "CREATE t:1 SET a = 1; DEFINE FIELD b ON TABLE t TYPE int DEFAULT 7");

        Datastore target = copy();

        assertEquals(json(source, "SELECT * FROM t"), json(target, "SELECT * FROM t"));
        run(target, "CREATE t:2 SET a = 2");
        assertEquals(7, run(target, "SELECT * FROM t:2").asObject().get("b").asLong());
    }

    @Test
    @DisplayName("embedding, campo ligado a ele e READONLY voltam na definição")
    void fieldDefinitionsSurvive() {
        run(source, "DEFINE EMBEDDING e PROVIDER http URL \"http://127.0.0.1:9/v1/embeddings\" MODEL \"m\" "
            + "DIMENSION 2 KEY ENV \"EMB_KEY\"");
        run(source, "DEFINE TABLE doc SCHEMALESS");
        run(source, "CREATE doc:1 SET texto = 'a', v = [1, 0]");
        run(source, "DEFINE FIELD v ON TABLE doc TYPE vector<2> EMBEDDING e FROM texto");
        run(source, "DEFINE FIELD fixo ON TABLE doc TYPE string READONLY");

        Datastore target = copy();

        assertEquals(catalog(source).embeddings(), catalog(target).embeddings());
        Catalog.FieldDef vector = catalog(target).table("doc").fields().get("v");
        assertNotNull(vector);
        assertEquals("e", vector.embeddingName());
        assertEquals("texto", vector.embeddingFrom());
        assertTrue(catalog(target).table("doc").fields().get("fixo").readonly());
        assertEquals(json(source, "SELECT * FROM doc"), json(target, "SELECT * FROM doc"));
    }
}
