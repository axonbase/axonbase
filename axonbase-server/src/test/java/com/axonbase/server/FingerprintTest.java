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
}
