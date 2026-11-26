package com.axonbase.core.cluster;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Atualização: compatibilidade de versões")
class VersionCompatibilityTest {

    private static VersionCompatibility.Check check(String target, String... versions) {
        Map<String, String> members = new java.util.LinkedHashMap<>();
        for (int i = 0; i < versions.length; i++) {
            members.put("n" + (i + 1), versions[i]);
        }
        return VersionCompatibility.check(members, target);
    }

    @Test
    @DisplayName("permite subir um nó de cada vez, inclusive no meio da atualização")
    void rollingStepsAreAllowed() {
        assertTrue(check("1.0.0", "0.10.1", "0.10.1", "0.10.1").allowed());
        assertTrue(check("1.0.0", "1.0.0", "0.10.1", "0.10.1").allowed());
        assertTrue(check("1.0.0", "1.0.0", "1.0.0", "0.10.1").allowed());
        assertTrue(check("1.0.0", "1.0.0", "1.0.0", "1.0.0").allowed());
        assertTrue(check("1.0.1-rc1", "1.0.0", "1.0.0", "1.0.0").allowed());
    }

    @Test
    @DisplayName("recusa voltar de versão, três versões e membro antigo demais")
    void unsafeStepsAreRefused() {
        assertFalse(check("0.10.1", "1.0.0", "0.10.1", "0.10.1").allowed());
        assertFalse(check("1.1.0", "1.0.0", "0.10.1", "0.10.1").allowed());
        VersionCompatibility.Check old = check("1.0.0", "0.9.2", "0.9.2", "0.9.2");
        assertFalse(old.allowed());
        assertTrue(old.problems().getFirst().contains(VersionCompatibility.MINIMUM_PEER), old.problems().toString());
    }

    @Test
    @DisplayName("recusa entradas que não entende")
    void invalidInput() {
        assertFalse(check("um", "0.10.1").allowed());
        assertFalse(check("1.0.0", "dev").allowed());
        assertFalse(check("1.0.0", "99999999999.0.0").allowed());
        assertFalse(check("1.0.0").allowed());
        assertFalse(VersionCompatibility.check(Map.of("n1", "1.0.0"), null).allowed());
    }
}
