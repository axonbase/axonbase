package com.axonbase.sdk;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Escrita segura de dados dentro de um comando AxonQL. Use quando o valor não puder ir como parâmetro
 * ({@code $nome}), como nos comandos de SAGA.
 */
public final class AxonQlText {

    private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private AxonQlText() {
    }

    /** Literal de texto, em que a barra invertida é o caractere de escape (dobrar a aspa não vale). */
    public static String literal(String text) {
        Objects.requireNonNull(text, "text");
        // a quebra de linha escrita como \n impede que o valor saia de um comentário de linha do comando
        return "'" + text.replace("\\", "\\\\").replace("'", "\\'")
            .replace("\n", "\\n").replace("\r", "\\r") + "'";
    }

    /** Nome de SAGA: identificador simples segue como está; qualquer outro vai como literal de texto. */
    public static String sagaName(String name) {
        Objects.requireNonNull(name, "name");
        return SIMPLE_NAME.matcher(name).matches() ? name : literal(name);
    }
}
