package com.axonbase.sdk;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Escrita segura de dados dentro de um comando AxonQL. Use quando o valor não puder ir como parâmetro
 * ({@code $nome}), como nos comandos de SAGA.
 */
public final class AxonQlText {

    private static final Pattern SIMPLE_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private static final Pattern TABLE_NAME = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]*");
    private static final Pattern NUMERIC_KEY = Pattern.compile("-?\\d{1,18}(\\.\\d+)?");

    private AxonQlText() {
    }

    /** Nome de tabela, que não tem escape possível: fora do formato de identificador simples é recusado. */
    public static String tableName(String name) {
        Objects.requireNonNull(name, "table");
        if (!TABLE_NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("invalid table name: " + name);
        }
        return name;
    }

    /** Chave de record id a partir do valor cru: o que é número segue como número, o resto vai como texto. */
    public static String recordKey(String key) {
        Objects.requireNonNull(key, "recordId");
        return NUMERIC_KEY.matcher(key).matches() ? key : literal(key);
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
