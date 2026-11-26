package com.axonbase.core.partition;

/**
 * Formato das chaves de dados no tempo. Numa tabela particionada, registros e índices locais ficam sob o prefixo da
 * partição, {@code ns\0db\0tabela\0!p\0<início>\0}; os descritores ficam fora das tabelas, em {@code !part|}.
 */
public final class PartitionKeys {

    /** Começo, depois do prefixo da tabela, de tudo que pertence a uma partição. */
    public static final String MARK = "!p\u0000";
    /** Começo, depois do prefixo da tabela, das entradas de prazo de validade. */
    public static final String TTL_MARK = "!ttl\u0000";
    /** Começo, depois do prefixo da partição, das entradas do índice COLUMNAR. */
    static final String COLUMNAR_MARK = "!ci|";
    /** Começo de todo descritor de partição. */
    public static final String DESCRIPTORS = "!part|";

    private static final int WIDTH = 19;

    private PartitionKeys() {
    }

    /** Número não negativo com largura fixa: a ordem das chaves é a ordem dos números. */
    public static String pad(long value) {
        String digits = Long.toString(value);
        return "0".repeat(WIDTH - digits.length()) + digits;
    }

    public static String dbPrefix(String ns, String db) {
        return ns + '\u0000' + db + '\u0000';
    }

    public static String tablePrefix(String ns, String db, String table) {
        return dbPrefix(ns, db) + table + '\u0000';
    }

    public static String partitionPrefix(String ns, String db, String table, long start) {
        return tablePrefix(ns, db, table) + MARK + pad(start) + '\u0000';
    }

    public static String descriptorPrefix(String ns, String db, String table) {
        return DESCRIPTORS + ns + "|" + db + "|" + table + "|";
    }

    public static String descriptorKey(String ns, String db, String table, long start) {
        return descriptorPrefix(ns, db, table) + pad(start);
    }

    public static String generationKey(String ns, String db, String table) {
        return "!partgen|" + ns + "|" + db + "|" + table;
    }

    /** Presente enquanto as linhas anteriores ao TTL ainda recebem a entrada de vencimento. */
    public static String ttlBackfillKey(String ns, String db, String table) {
        return "!ttlfill|" + ns + "|" + db + "|" + table;
    }

    public static String bloomKey(String ns, String db, String table, long start) {
        return "!pbloom|" + ns + "|" + db + "|" + table + "|" + pad(start);
    }

    public static String ttlPrefix(String ns, String db, String table) {
        return tablePrefix(ns, db, table) + TTL_MARK;
    }

    public static String ttlKey(String ns, String db, String table, long expiry, String recordKey) {
        return ttlPrefix(ns, db, table) + pad(expiry) + '\u0000' + recordKey;
    }

    /** As chaves de registro começam por {@code n}, {@code s} ou {@code x} ({@code AxonValue.keyString}). */
    public static boolean isRecordTail(String tail) {
        if (tail.isEmpty()) {
            return false;
        }
        char lead = tail.charAt(0);
        return lead == 'n' || lead == 's' || lead == 'x';
    }

    /** Um registro identificado pela chave física. */
    public record StoredRecord(String table, String recordKey) {
    }

    /**
     * O registro guardado numa chave física, particionada ou não. {@code body} é a chave sem o prefixo do banco.
     * Devolve {@code null} para índices e demais chaves que não são registros.
     */
    public static StoredRecord record(String body) {
        int separator = body.indexOf('\u0000');
        if (separator < 0) {
            return null;
        }
        String table = body.substring(0, separator);
        String rest = body.substring(separator + 1);
        if (rest.startsWith(MARK)) {
            int afterStart = MARK.length() + WIDTH + 1;
            if (rest.length() <= afterStart) {
                return null;
            }
            rest = rest.substring(afterStart);
        }
        return isRecordTail(rest) ? new StoredRecord(table, rest) : null;
    }
}
