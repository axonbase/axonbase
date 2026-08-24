package com.axonbase.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Test de ficheiros de consulta AxonQL: cada arquivo en
 * {@code src/test/resources/queries} debe parsear e renderizar ao mesmo texto
 * canónico (round-trip), equivalente aos language-tests de SurrealDB.
 */
@DisplayName("AxonQL: ficheiros de consulta (.axonql)")
class QueryFileTest {

    @Test
    void roundTripCreate() {
        assertFileRoundTrip("queries/create.axonql");
    }

    @Test
    void roundTripSelect() {
        assertFileRoundTrip("queries/select.axonql");
    }

    @Test
    void roundTripInsert() {
        assertFileRoundTrip("queries/insert.axonql");
    }

    @Test
    void roundTripRelate() {
        assertFileRoundTrip("queries/relate.axonql");
    }

    @Test
    void roundTripDefineField() {
        assertFileRoundTrip("queries/define_field.axonql");
    }

    @Test
    void roundTripIfElse() {
        assertFileRoundTrip("queries/ifelse.axonql");
    }

    private static void assertFileRoundTrip(String resource) {
        String text = readResource(resource).trim();
        String rendered = AxonQl.render(AxonQl.parse(text));
        assertEquals(text, rendered, "round-trip do ficheiro " + resource);
    }

    private static String readResource(String path) {
        try (InputStream in = QueryFileTest.class.getClassLoader().getResourceAsStream(path)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}