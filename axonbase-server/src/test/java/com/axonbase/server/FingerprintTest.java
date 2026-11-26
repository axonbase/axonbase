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
    @DisplayName("leituras por id compartilham o fingerprint, seja o id texto, número, entre crases ou entre ⟨⟩")
    void recordKeysCollapse() {
        assertEquals("SELECT * FROM item:?", Fingerprint.of("SELECT * FROM item:i1"));
        assertEquals("SELECT * FROM item:?", Fingerprint.of("SELECT * FROM item:i99999"));
        assertEquals("SELECT * FROM item:?", Fingerprint.of("SELECT * FROM item:7"));
        assertEquals("CREATE pedido:? CONTENT { n: ? }",
            Fingerprint.of("CREATE pedido:`raw64-1-2` CONTENT { n: 7 }"));
        assertEquals("SELECT * FROM pessoa:?", Fingerprint.of("SELECT * FROM pessoa:⟨ana@x.com⟩"));
        assertEquals("RELATE pessoa:?->segue->pessoa:?", Fingerprint.of("RELATE pessoa:ana->segue->pessoa:bia"));
    }

    @Test
    @DisplayName("caminhos de função com :: e objetos com espaço ficam como estão")
    void functionPathsAndObjectsSurvive() {
        String sql = "SELECT vector::distance::euclidean(v, $q) AS d FROM t WHERE o = { campo: $v }";
        assertEquals(sql, Fingerprint.of(sql));
    }

    @Test
    @DisplayName("colapsa espaços e limita o tamanho")
    void collapsesAndTruncates() {
        assertEquals("SELECT * FROM t", Fingerprint.of("  SELECT   *\n FROM\tt  "));
        assertTrue(Fingerprint.of("SELECT " + "x, ".repeat(500) + "y FROM t").length() <= Fingerprint.MAX_CHARS + 3);
        assertEquals("", Fingerprint.of(null));
    }

    // ---- equivalência com a implementação por expressões regulares (a anterior, mais a chave de registro) ----

    private static final java.util.regex.Pattern RECORD_KEYS =
        java.util.regex.Pattern.compile("(?<=[A-Za-z0-9_`⟩]):(?:[A-Za-z0-9_]+|`[^`]*`|⟨[^⟩]*⟩)");

    private static final java.util.regex.Pattern STRINGS = java.util.regex.Pattern.compile("'(?:[^'\\\\]|\\\\.)*'|\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final java.util.regex.Pattern NUMBERS = java.util.regex.Pattern.compile("\\b\\d+(?:\\.\\d+)?\\b");
    private static final java.util.regex.Pattern LISTS = java.util.regex.Pattern.compile("\\?(?:\\s*,\\s*\\?)+");
    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile("\\s+");

    private static String legacy(String sql) {
        String s = STRINGS.matcher(sql).replaceAll("?");
        s = RECORD_KEYS.matcher(s).replaceAll(":?");
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
