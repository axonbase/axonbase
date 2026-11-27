package com.axonbase.parser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** O texto renderizado é o que fica gravado e o que sai no dump: relido, precisa dar a mesma árvore. */
@DisplayName("Render: parênteses e citação sobrevivem à ida e volta")
class RenderPrecedenceTest {

    @ParameterizedTest
    @ValueSource(strings = {
        "CREATE TABLE t (a int, CHECK ((a = 1 OR a = 2) AND a != 3))",
        "CREATE TABLE t (a int CHECK ((a > 10 OR a < 0) AND a != 50))",
        "ALTER TABLE t ADD COLUMN a int CHECK (!(a > 1 AND a < 5))",
        "DEFINE FIELD a ON TABLE t ASSERT ($value = 1 OR $value = 2) AND $value != 3",
        "DEFINE TABLE t SCHEMALESS PERMISSIONS FOR select WHERE (dono = $auth.id OR publico = true) AND ativo = true",
        "CREATE DATA RULE r APPLY (a = 1 OR b = 2) AND c = 3",
        "RETURN (a + b) * c",
        "RETURN a - (b - c)",
        "RETURN (a - b) - c",
        "RETURN (2 ** 3) ** 2",
        "RETURN 2 ** (3 ** 2)",
        "RETURN -(a + b)",
        "RETURN -(-a)",
        "RETURN a - -1",
        "RETURN (a + b).len()",
        "RETURN (-a).abs()",
        "RETURN <int> (a + b)",
        "RETURN (a ?? b) AND c",
        "RETURN a NOT IN [1, 2]",
        "RETURN a BETWEEN 1 AND (b + 1)",
        "RETURN !(a BETWEEN 1 AND 2)",
        "SELECT `first name`, `a b`.c AS `d e` FROM `order items` WHERE `check` = 1",
        "CREATE `order items`:`a b` SET `x y` = {\"a: 1}; DELETE t; --\": 1, \"select\": 2}",
        "CREATE t:\"7\" SET a = t:7",
        "DEFINE INDEX `i x` ON TABLE `order items` COLUMNS `a b`, c UNIQUE",
        "CREATE TABLE t (`check` string, `primary` string, `a b` int)",
        "BEGIN SAGA s WITH CORRELATION 'a\\'; DELETE t; --'"
    })
    void roundTrip(String sql) {
        String rendered = AxonQl.render(AxonQl.parse(sql));

        assertEquals(AxonQl.parse(sql), AxonQl.parse(rendered), rendered);
    }
}
