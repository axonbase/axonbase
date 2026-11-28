package com.axonbase.core.audit;

import com.axonbase.value.AxonValue;

import java.util.Map;

/**
 * Quem pediu o comando retido pela auditoria: os claims, as variáveis da requisição e a marca de somente leitura.
 * É com isso, e não com a sessão do aprovador, que o comando aprovado é executado.
 */
public record AuditAuthor(AxonValue claims, Map<String, AxonValue> vars, boolean readOnly) {

    public AuditAuthor {
        vars = vars == null ? Map.of() : Map.copyOf(vars);
    }
}
