package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Arestas: UNRELATE e cascata; travessia encadeada, campo e FETCH; DROP TABLE persistente")
class GraphEdgesAndTraversalTest {

    private Datastore ds;
    private Session session;

    @BeforeEach
    void setUp() {
        ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        session = session();
        // ana -> bia, ana -> caio, bia -> caio, caio -> dani ; compras e nomes
        run("CREATE pessoa:ana CONTENT {nome: 'Ana'}; CREATE pessoa:bia CONTENT {nome: 'Bia'}; "
            + "CREATE pessoa:caio CONTENT {nome: 'Caio'}; CREATE pessoa:dani CONTENT {nome: 'Dani'}; "
            + "CREATE produto:mouse CONTENT {nome: 'Mouse'}; CREATE produto:notebook CONTENT {nome: 'Notebook'}");
        run("RELATE pessoa:ana->segue->pessoa:bia; RELATE pessoa:ana->segue->pessoa:caio; "
            + "RELATE pessoa:bia->segue->pessoa:caio; RELATE pessoa:caio->segue->pessoa:dani; "
            + "RELATE pessoa:ana->comprou->produto:mouse; RELATE pessoa:ana->comprou->produto:notebook");
    }

    private static Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    /** Ids de uma coluna que pode vir como valor único ou como lista. */
    private static List<String> ids(AxonValue value) {
        List<String> out = new ArrayList<>();
        if (value.isArray()) {
            value.asArray().forEach(v -> out.add(v.isString() ? v.asString() : v.toString()));
        } else if (!value.isNull() && !value.isNone()) {
            out.add(value.isString() ? value.asString() : value.toString());
        }
        out.sort(String::compareTo);
        return out;
    }

    private List<String> follows(String from) {
        return ids(run("SELECT ->segue->pessoa AS r FROM " + from).asObject().get("r"));
    }

    private List<String> followers(String of) {
        return ids(run("SELECT <-segue<-pessoa AS r FROM " + of).asObject().get("r"));
    }

    // ---------------------------------------------------------------- UNRELATE

    @Test
    @DisplayName("UNRELATE a->tipo->b remove só essa aresta, nas duas direções")
    void unrelateRemovesOneEdge() {
        AxonValue removed = run("UNRELATE pessoa:ana->segue->pessoa:bia");
        assertEquals(1, removed.asArray().size());
        assertEquals(List.of("pessoa:caio"), follows("pessoa:ana"));
        assertEquals(List.of(), followers("pessoa:bia"));
        assertEquals(List.of("pessoa:ana", "pessoa:bia"), ids(run("SELECT <-segue<-pessoa AS r FROM pessoa:caio").asObject().get("r")));
    }

    @Test
    @DisplayName("UNRELATE a->tipo remove todas as arestas de saída desse tipo e preserva as de outros tipos")
    void unrelateRemovesAllOfAKind() {
        AxonValue removed = run("UNRELATE pessoa:ana->segue");
        assertEquals(2, removed.asArray().size());
        assertEquals(List.of(), follows("pessoa:ana"));
        assertEquals(List.of(), followers("pessoa:bia"));
        assertEquals(List.of("pessoa:dani"), follows("pessoa:caio"), "arestas de outros vértices ficam");
        assertEquals(List.of("produto:mouse", "produto:notebook"),
            ids(run("SELECT ->comprou->produto AS r FROM pessoa:ana").asObject().get("r")), "outro tipo fica");
    }

    @Test
    @DisplayName("UNRELATE de uma aresta que não existe devolve lista vazia e repetir não falha")
    void unrelateMissingEdge() {
        assertEquals(0, run("UNRELATE pessoa:dani->segue->pessoa:ana").asArray().size());
        run("UNRELATE pessoa:ana->segue->pessoa:bia");
        assertEquals(0, run("UNRELATE pessoa:ana->segue->pessoa:bia").asArray().size());
    }

    @Test
    @DisplayName("UNRELATE é reescrito igual no Render (replay) e não reserva a palavra")
    void unrelateParsesAndStaysAnIdentifier() {
        run("CREATE t:1 CONTENT {unrelate: 7}");
        assertEquals(7, run("SELECT VALUE unrelate FROM t").asArray().get(0).asLong());
    }

    @Test
    @DisplayName("UNRELATE em DATABASE LINK dá erro claro")
    void unrelateOnLinkFails() {
        assertThrows(AxonError.class, () -> run("UNRELATE \"remoto\".\"pessoa\":ana->segue->\"remoto\".\"pessoa\":bia"));
    }

    // ----------------------------------------------- cascata no DELETE do registro

    @Test
    @DisplayName("DELETE de um registro apaga as arestas dele, de entrada e de saída")
    void deleteCascadesEdges() {
        run("DELETE pessoa:bia");
        assertEquals(List.of("pessoa:caio"), follows("pessoa:ana"), "ana->bia saiu");
        assertEquals(List.of("pessoa:ana"), followers("pessoa:caio"), "bia->caio saiu");
        assertEquals(List.of("pessoa:dani"), follows("pessoa:caio"));
    }

    @Test
    @DisplayName("DELETE em massa continua funcionando com e sem arestas no banco")
    void bulkDeleteWithAndWithoutEdges() {
        run("DELETE pessoa");
        assertEquals(0, run("SELECT VALUE count() FROM pessoa").asLong());
        // sem aresta alguma
        Datastore plain = Datastore.memory();
        plain.createDatabase("test", "dev");
        plain.execute("CREATE x:1 CONTENT {a: 1}; CREATE x:2 CONTENT {a: 2}; DELETE x", session(), null);
        assertEquals(0, plain.execute("SELECT VALUE count() FROM x", session(), null).asLong());
    }

    // ---------------------------------------------- DROP TABLE de um tipo de aresta

    @Test
    @DisplayName("DROP TABLE de um tipo de aresta leva as arestas dele e preserva as outras")
    void dropEdgeKindRemovesItsEdges() {
        run("DROP TABLE segue");
        assertEquals(List.of(), follows("pessoa:ana"));
        assertEquals(List.of(), followers("pessoa:caio"));
        assertEquals(List.of("produto:mouse", "produto:notebook"),
            ids(run("SELECT ->comprou->produto AS r FROM pessoa:ana").asObject().get("r")));
    }

    // ------------------------------------------------- DROP TABLE persistente

    private static AxonValue info(Datastore store) {
        return store.execute("INFO FOR DATABASE", session(), null).asObject().get("details");
    }

    @Test
    @DisplayName("DROP TABLE sobrevive ao reinício, junto com campos e índices da tabela")
    void dropTableSurvivesRestart() {
        MemoryBackend backend = new MemoryBackend();
        Datastore first = new Datastore(backend);
        first.createDatabase("test", "dev");
        Session s = session();
        first.execute("DEFINE TABLE velha SCHEMALESS; DEFINE FIELD nome ON TABLE velha TYPE string; "
            + "DEFINE INDEX velha_nome ON TABLE velha COLUMNS nome; CREATE velha:1 CONTENT {nome: 'x'}; "
            + "DEFINE TABLE fica SCHEMALESS; DEFINE FIELD nome ON TABLE fica TYPE string", s, null);
        assertTrue(info(first).asObject().containsKey("velha"));

        first.execute("DROP TABLE velha", s, null);
        assertFalse(info(first).asObject().containsKey("velha"));

        Datastore second = new Datastore(backend);
        assertFalse(info(second).asObject().containsKey("velha"), "a tabela não pode voltar no reinício");
        assertTrue(info(second).asObject().containsKey("fica"));
        assertTrue(info(second).asObject().get("fica").asObject().get("fields").asObject().containsKey("nome"),
            "o que é de outra tabela fica intacto");
        // e o nome está livre para uma tabela nova, que também persiste
        second.execute("DEFINE TABLE velha SCHEMAFULL", session(), null);
        Datastore third = new Datastore(backend);
        assertEquals("SCHEMAFULL", info(third).asObject().get("velha").asObject().get("schema").asString());
        assertEquals(0, third.execute("SELECT VALUE count() FROM velha", session(), null).asLong(), "dados antigos não voltam");
    }

    @Test
    @DisplayName("tipo de aresta apagado com DROP TABLE não volta no reinício")
    void droppedEdgeKindStaysDropped() {
        MemoryBackend backend = new MemoryBackend();
        Datastore first = new Datastore(backend);
        first.createDatabase("test", "dev");
        first.execute("RELATE a:1->amizade->a:2", session(), null);
        assertTrue(info(first).asObject().containsKey("amizade"));
        first.execute("DROP TABLE amizade", session(), null);
        Datastore second = new Datastore(backend);
        assertFalse(info(second).asObject().containsKey("amizade"));
    }

    // ------------------------------------------------- travessia encadeada

    @Test
    @DisplayName("->segue->pessoa->segue->pessoa segue duas vezes (amigos de amigos)")
    void chainedTraversal() {
        assertEquals(List.of("pessoa:caio", "pessoa:dani"),
            ids(run("SELECT ->segue->pessoa->segue->pessoa AS r FROM pessoa:ana").asObject().get("r")));
        // três saltos a partir da ana: {bia, caio} -> {caio, dani} -> {dani}
        assertEquals(List.of("pessoa:dani"),
            ids(run("SELECT ->segue->pessoa->segue->pessoa->segue->pessoa AS r FROM pessoa:ana").asObject().get("r")));
    }

    @Test
    @DisplayName("travessia encadeada de entrada e mista")
    void chainedIncomingAndMixed() {
        assertEquals(List.of("pessoa:ana", "pessoa:bia"),
            ids(run("SELECT <-segue<-pessoa<-segue<-pessoa AS r FROM pessoa:dani").asObject().get("r")));
        assertEquals(List.of("produto:mouse", "produto:notebook"),
            ids(run("SELECT <-segue<-pessoa->comprou->produto AS r FROM pessoa:bia").asObject().get("r")));
    }

    // --------------------------------------------- campo depois da travessia

    @Test
    @DisplayName("->tipo->tabela.campo devolve o campo de cada registro alcançado")
    void fieldAfterTraversal() {
        assertEquals(List.of("Mouse", "Notebook"),
            ids(run("SELECT ->comprou->produto.nome AS nomes FROM pessoa:ana").asObject().get("nomes")));
        assertEquals(List.of("Bia", "Caio"),
            ids(run("SELECT ->segue->pessoa.nome AS nomes FROM pessoa:ana").asObject().get("nomes")));
        // um só resultado volta como valor único, como as demais travessias
        assertEquals(List.of("Dani"), ids(run("SELECT ->segue->pessoa.nome AS n FROM pessoa:caio").asObject().get("n")));
        // sem arestas: lista vazia
        assertEquals(List.of(), ids(run("SELECT ->segue->pessoa.nome AS n FROM pessoa:dani").asObject().get("n")));
    }

    // ------------------------------------------------------- FETCH em apelido

    @Test
    @DisplayName("FETCH expande a coluna calculada por uma travessia")
    void fetchOnTraversalAlias() {
        AxonValue row = run("SELECT id, ->comprou->produto AS p FROM pessoa:ana FETCH p");
        AxonValue products = row.asObject().get("p");
        assertTrue(products.isArray());
        assertEquals(2, products.asArray().size());
        assertTrue(products.asArray().stream().allMatch(AxonValue::isObject));
        List<String> names = new ArrayList<>();
        products.asArray().forEach(p -> names.add(p.asObject().get("nome").asString()));
        names.sort(String::compareTo);
        assertEquals(List.of("Mouse", "Notebook"), names);
    }

    @Test
    @DisplayName("id literal e id vindo da travessia são o mesmo registro (=, IN, graph::path)")
    void literalAndTraversedIdsAreEqual() {
        assertTrue(run("RETURN pessoa:caio IN pessoa:ana->segue->pessoa").asBool());
        assertTrue(run("RETURN pessoa:bia->segue->pessoa = pessoa:caio").asBool());
        assertTrue(run("RETURN pessoa:dani IN pessoa:ana->segue->pessoa->segue->pessoa").asBool());
        assertFalse(run("RETURN pessoa:dani IN pessoa:ana->segue->pessoa").asBool());
        assertEquals(3, run("RETURN graph::path(pessoa:ana, pessoa:dani)").asArray().size());
        assertTrue(run("RETURN graph::connected(pessoa:ana, pessoa:dani)").asBool());
        assertFalse(run("RETURN graph::connected(pessoa:dani, pessoa:ana)").asBool());
        assertEquals(0, run("RETURN graph::path(pessoa:dani, pessoa:ana)").asArray().size());
    }
}
