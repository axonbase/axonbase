package com.axonbase.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AxonJson: codec manual")
class AxonJsonTest {

    @Test
    void escribeValoresSimples() {
        assertEquals("null", AxonJson.write(AxonValue.nul()));
        assertEquals("true", AxonJson.write(AxonValue.bool(true)));
        assertEquals("42", AxonJson.write(AxonValue.num(42L)));
        assertEquals("3.5", AxonJson.write(AxonValue.num(3.5)));
        assertEquals("\"ola\"", AxonJson.write(AxonValue.str("ola")));
    }

    @Test
    void escribeObjecto() {
        Map<String, AxonValue> dados = new java.util.LinkedHashMap<>();
        dados.put("nome", AxonValue.str("Ana"));
        dados.put("idade", AxonValue.num(30));
        AxonValue obj = AxonValue.object(dados);
        String json = AxonJson.write(obj);
        assertEquals("{\"nome\":\"Ana\",\"idade\":30}", json);
    }

    @Test
    void escribeArray() {
        AxonValue arr = AxonValue.array(List.of(AxonValue.num(1), AxonValue.num(2)));
        assertEquals("[1,2]", AxonJson.write(arr));
    }

    @Test
    void roundTripObjecto() {
        AxonValue original = AxonValue.object(Map.of(
                "nome", AxonValue.str("Ana"),
                "idade", AxonValue.num(30),
                "ativo", AxonValue.bool(true),
                "tags", AxonValue.array(List.of(AxonValue.str("a"), AxonValue.str("b")))));
        String json = AxonJson.write(original);
        AxonValue parsed = AxonJson.parseDocument(json);
        assertEquals(original, parsed);
    }

    @Test
    void escapeEnString() {
        assertEquals("\"a\\\"b\"", AxonJson.write(AxonValue.str("a\"b")));
    }

    @Test
    void parseInvalidoFalla() {
        assertThrows(com.axonbase.common.AxonError.class, () -> AxonJson.parseDocument("{invalido"));
    }
}