package com.axonbase.value;

import com.axonbase.common.Messages;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.math.BigDecimal;

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

    @Test
    void parseEscapeUnicodeNaoRepeteDigitosHexadecimais() {
        assertEquals("café", AxonJson.parseDocument("\"caf\\u00e9\"").asString());
    }

    @Test
    void preservaDecimalGrandeSemConverterParaDouble() {
        AxonValue value = AxonValue.num(new BigDecimal("12345678901234567890.123456789"));
        assertEquals("{\"$decimal\":\"12345678901234567890.123456789\"}", AxonJson.write(value));
        assertEquals(value, AxonJson.parseDocument(AxonJson.write(value)));
    }

    @Test
    void serializaBytesComoBase64Marcado() {
        assertEquals("{\"$bytes\":\"SGk=\"}", AxonJson.write(AxonValue.bytes(new byte[] {72, 105})));
    }

    @Test
    void serializaEDeserializaDatetimeMarcado() {
        var t = java.time.Instant.parse("2026-01-01T00:00:00Z");
        assertEquals("{\"$datetime\":\"2026-01-01T00:00:00Z\"}", AxonJson.write(AxonValue.datetime(t)));
        assertEquals(AxonValue.datetime(t), AxonJson.parseDocument("{\"$datetime\":\"2026-01-01T00:00:00Z\"}"));
    }

    @Test
    void serializaEDeserializaDuracaoMarcada() {
        assertEquals("{\"$duration\":\"1h\"}", AxonJson.write(AxonValue.duration(3_600_000L)));
        assertEquals(AxonValue.duration(3_600_000L),
            AxonJson.parseDocument("{\"$duration\":\"1h\"}"));
        assertEquals("{\"$duration\":\"90m\"}", AxonJson.write(AxonValue.duration(90 * 60_000L)));
        assertEquals(AxonValue.duration(90 * 60_000L),
            AxonJson.parseDocument("{\"$duration\":\"1h30m\"}"));
    }

    @Test
    void serializaEDeserializaUuidETabelaMarcados() {
        var u = java.util.UUID.fromString("73e9a5c7-91f2-4b6e-b9a9-123456789abc");
        assertEquals("{\"$uuid\":\"73e9a5c7-91f2-4b6e-b9a9-123456789abc\"}",
            AxonJson.write(AxonValue.uuid(u)));
        assertEquals(AxonValue.uuid(u),
            AxonJson.parseDocument("{\"$uuid\":\"73e9a5c7-91f2-4b6e-b9a9-123456789abc\"}"));
        assertEquals("{\"$table\":\"person\"}", AxonJson.write(AxonValue.table("person")));
        assertEquals(AxonValue.table("person"),
            AxonJson.parseDocument("{\"$table\":\"person\"}"));
    }

    @Test
    void deserializaRecordMarcado() {
        AxonValue r = AxonJson.parseDocument("{\"$record\":\"person:ana\"}");
        assertEquals(AxonValue.record("person", "ana"), r);
        assertTrue(r.isRecordId());
    }

    @Test
    void taggingInvalidoFalla() {
        assertThrows(com.axonbase.common.AxonError.class,
            () -> AxonJson.parseDocument("{\"$datetime\":\"nao-e-data\"}"));
        assertThrows(com.axonbase.common.AxonError.class,
            () -> AxonJson.parseDocument("{\"$record\":\"sem-tabela\"}"));
    }

    @Test
    void mensagensDeParsePreservamDetalhesEmIngles() {
        assertEquals("JSON error: invalid record: person:ana",
            Messages.getForLanguage("en", "json_parse_error",
                Messages.getForLanguage("en", "json_invalid_record", "person:ana")));
    }
}
