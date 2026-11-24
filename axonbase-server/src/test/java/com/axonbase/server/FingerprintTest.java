package com.axonbase.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Fingerprint de instruções")
class FingerprintTest {

    @Test
    @DisplayName("troca textos e números por ? e mantém a estrutura")
    void replacesLiterals() {
        assertEquals("SELECT * FROM t WHERE email = ? AND n > ?",
            Fingerprint.of("SELECT * FROM t WHERE email = 'ana@x.com' AND n > 42"));
        assertEquals("SELECT * FROM t WHERE nome = ?", Fingerprint.of("SELECT * FROM t WHERE nome = \"Joao\""));
    }

    @Test
    @DisplayName("consultas que só diferem nos valores têm o mesmo fingerprint")
    void sameShapeSameFingerprint() {
        assertEquals(Fingerprint.of("SELECT * FROM t:1"), Fingerprint.of("SELECT * FROM t:99"));
        assertEquals(Fingerprint.of("SELECT * FROM t WHERE id IN [1, 2, 3]"),
            Fingerprint.of("SELECT * FROM t WHERE id IN [4,5,6,7,8]"));
    }

    @Test
    @DisplayName("não mexe em identificadores com dígitos e não vaza o valor")
    void keepsIdentifiersAndDropsValues() {
        String fp = Fingerprint.of("SELECT col1 FROM tabela2 WHERE cpf = '123.456.789-00' AND senha = 'abc\\'def'");
        assertTrue(fp.contains("col1") && fp.contains("tabela2"), fp);
        assertFalse(fp.contains("123") && fp.contains("abc"), fp);
        assertFalse(fp.contains("789"), fp);
    }

    @Test
    @DisplayName("colapsa espaços e limita o tamanho")
    void collapsesAndTruncates() {
        assertEquals("SELECT * FROM t", Fingerprint.of("  SELECT   *\n FROM\tt  "));
        assertTrue(Fingerprint.of("SELECT " + "x, ".repeat(500) + "y FROM t").length() <= Fingerprint.MAX_CHARS + 3);
        assertEquals("", Fingerprint.of(null));
    }

    // ---- equivalência com a implementação anterior (quatro expressões regulares) ----

    private static final java.util.regex.Pattern STRINGS = java.util.regex.Pattern.compile("'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final java.util.regex.Pattern NUMBERS = java.util.regex.Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final java.util.regex.Pattern LISTS = java.util.regex.Pattern.compile("\\?(?:\\s*,\\s*\\?)+");
    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile("\\s+");

    private static String legacy(String sql) {
        String s = STRINGS.matcher(sql).replaceAll("?");
        s = NUMBERS.matcher(s).replaceAll("?");
        s = LISTS.matcher(s).replaceAll("?");
        s = SPACES.matcher(s).replaceAll(" ").trim();
        return s.length() > Fingerprint.MAX_CHARS ? s.substring(0, Fingerprint.MAX_CHARS) + "..." : s;
    }

    @org.junit.jupiter.api.Test
    void igualAImplementacaoAnteriorEmCentenasDeMilharesDeEntradas() {
        String[] pieces = {"SELECT", "*", "FROM", "pedido", "pedido:1", "c123", "x1y", "WHERE", "id", "=", "<=", "AND", "'abc'", "'a\\'b'",
            "\"dois\"", "12", "3.14", "007", "1.", ".5", "1e5", "12abc", "abc12", ",", "(", ")", "[", "]", "{a: 1}", "?", "IN", "LIMIT", "10",
            "\n", "\t", "  ", "'", "\"", "\\", "->", "$x", "9999999999", "1.2.3", "a.b", "a,b,c", "', '", "1,2,3"};
        java.util.Random r = new java.util.Random(42);
        for (int n = 0; n < 200_000; n++) {
            StringBuilder sb = new StringBuilder();
            int len = 1 + r.nextInt(18);
            for (int k = 0; k < len; k++) {
                sb.append(pieces[r.nextInt(pieces.length)]);
                if (r.nextInt(3) > 0) {
                    sb.append(r.nextBoolean() ? " " : "");
                }
            }
            String sql = sb.toString();
            org.junit.jupiter.api.Assertions.assertEquals(legacy(sql), Fingerprint.of(sql), "entrada: [" + sql + "]");
        }
    }

    @org.junit.jupiter.api.Test
    void textosLongosSaemCortadosComoAntes() {
        String sql = "SELECT " + "campo_muito_longo_numero_".repeat(40) + " FROM t WHERE a IN (1, 2, 3)";
        org.junit.jupiter.api.Assertions.assertEquals(legacy(sql), Fingerprint.of(sql));
        String lista = "SELECT * FROM t WHERE id IN (" + "1, ".repeat(400) + "2)";
        org.junit.jupiter.api.Assertions.assertEquals(legacy(lista), Fingerprint.of(lista));
    }
}
