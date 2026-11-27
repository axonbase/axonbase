package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** SET e PATCH alteram o caminho indicado e MERGE junta objetos internos. */
@DisplayName("Escritas em caminhos aninhados")
class NestedWritesTest {

    private final Datastore ds = new Datastore(new MemoryBackend());

    private AxonValue run(String sql) {
        Session s = Session.create();
        s.namespace("n");
        s.database("d");
        return ds.execute(sql, s, null);
    }

    private void seed() {
        run("CREATE p:1 CONTENT {addr: {city: 'SP', zip: '1', geo: {lat: 1, lon: 2}}, tags: ['a', 'b'], idade: 20}");
    }

    /** O registro com os campos em ordem alfabética, para comparar sem depender da ordem de gravação. */
    private String stored(String target) {
        AxonValue rows = run("SELECT * FROM " + target);
        AxonValue row = rows.isArray() ? rows.asArray().get(0) : rows;
        return AxonJson.write(sorted(row));
    }

    private static AxonValue sorted(AxonValue value) {
        if (value.isObject()) {
            Map<String, AxonValue> out = new TreeMap<>();
            value.asObject().forEach((key, item) -> out.put(key, sorted(item)));
            return AxonValue.object(out);
        }
        return value.isArray() ? AxonValue.array(value.asArray().stream().map(NestedWritesTest::sorted).toList()) : value;
    }

    private String field(String name) {
        AxonValue rows = run("SELECT * FROM p:1");
        AxonValue row = rows.isArray() ? rows.asArray().get(0) : rows;
        AxonValue value = row.asObject().get(name);
        return value == null ? null : AxonJson.write(sorted(value));
    }

    @Test
    @DisplayName("SET addr.city altera dentro de addr e não cria campo na raiz")
    void setNestedField() {
        seed();

        run("UPDATE p:1 SET addr.city = 'RJ', addr.geo.lat = 9");

        assertEquals("{\"city\":\"RJ\",\"geo\":{\"lat\":9,\"lon\":2},\"zip\":\"1\"}", field("addr"));
        assertEquals(null, field("city"));
        assertEquals(null, field("lat"));
    }

    @Test
    @DisplayName("SET cria os objetos que faltam e altera posições de lista")
    void setCreatesAndIndexes() {
        seed();

        run("UPDATE p:1 SET contato.fone.ddd = '11', tags[1] = 'z', tags[2] = 'c'");

        assertEquals("{\"fone\":{\"ddd\":\"11\"}}", field("contato"));
        assertEquals("[\"a\",\"z\",\"c\"]", field("tags"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"idade.anos = 1", "tags[7] = 'x'", "addr[0] = 'x'", "tags.nome = 'x'"})
    @DisplayName("um caminho que não existe naquele formato é recusado sem gravar nada")
    void impossiblePathIsRejected(String assignment) {
        seed();
        String before = stored("p:1");

        assertThrows(RuntimeException.class, () -> run("UPDATE p:1 SET " + assignment));

        assertEquals(before, stored("p:1"));
    }

    @Test
    @DisplayName("CREATE e UPSERT com SET aninhado montam o objeto")
    void createWithNestedSet() {
        run("CREATE p:1 SET addr.city = 'SP', addr.zip = '1'");
        run("UPSERT p:2 SET addr.city = 'RJ'");

        assertEquals("{\"addr\":{\"city\":\"SP\",\"zip\":\"1\"},\"id\":\"p:1\"}", stored("p:1"));
        assertEquals("{\"addr\":{\"city\":\"RJ\"},\"id\":\"p:2\"}", stored("p:2"));
    }

    @Test
    @DisplayName("o apelido ou o nome da tabela antes do campo continuam valendo")
    void qualifiedColumnStillWorks() {
        seed();

        run("UPDATE p t SET t.idade = 21");
        run("UPDATE p:1 SET p.idade = 22");

        assertEquals("22", field("idade"));
        assertEquals(null, field("t"));
        assertEquals(null, field("p"));
    }

    @Test
    @DisplayName("MERGE junta objetos internos e substitui listas e valores")
    void mergeIsDeep() {
        seed();

        run("UPDATE p:1 MERGE {addr: {zip: '2', geo: {lon: 5}}, tags: ['c']}");

        assertEquals("{\"city\":\"SP\",\"geo\":{\"lat\":1,\"lon\":5},\"zip\":\"2\"}", field("addr"));
        assertEquals("[\"c\"]", field("tags"));
        assertEquals("20", field("idade"));
    }

    @Test
    @DisplayName("PATCH em lista aplica as operações na ordem")
    void patchList() {
        seed();

        run("UPDATE p:1 PATCH [{op: 'replace', path: '/idade', value: 51}, {op: 'add', path: '/addr/uf', value: 'SP'}, "
            + "{op: 'remove', path: '/addr/geo'}, {op: 'add', path: '/tags/-', value: 'c'}, "
            + "{op: 'add', path: '/tags/0', value: 'z'}, {op: 'move', from: '/addr/zip', path: '/cep'}, "
            + "{op: 'copy', from: '/addr/city', path: '/cidade'}, {op: 'test', path: '/idade', value: 51}]");

        assertEquals("51", field("idade"));
        assertEquals("{\"city\":\"SP\",\"uf\":\"SP\"}", field("addr"));
        assertEquals("[\"z\",\"a\",\"b\",\"c\"]", field("tags"));
        assertEquals("\"1\"", field("cep"));
        assertEquals("\"SP\"", field("cidade"));
    }

    @Test
    @DisplayName("PATCH com um objeto continua incorporando os campos")
    void patchObject() {
        seed();

        run("UPDATE p:1 PATCH {idade: 50}");

        assertEquals("50", field("idade"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "[{op: 'replace', path: '/idade', value: 51}, {op: 'replace', path: '/nada', value: 1}]",
        "[{op: 'remove', path: '/addr/nada'}]",
        "[{op: 'add', path: '/tags/9', value: 'x'}]",
        "[{op: 'test', path: '/idade', value: 99}]",
        "[{op: 'inventada', path: '/idade'}]",
        "[{op: 'replace', path: 'idade', value: 1}]",
        "[{op: 'replace', path: '/id', value: 'p:9'}]",
        "42"})
    @DisplayName("PATCH inválido é recusado sem gravar nada")
    void invalidPatchIsRejected(String patch) {
        seed();
        String before = stored("p:1");

        assertThrows(RuntimeException.class, () -> run("UPDATE p:1 PATCH " + patch));

        assertEquals(before, stored("p:1"));
    }
}
