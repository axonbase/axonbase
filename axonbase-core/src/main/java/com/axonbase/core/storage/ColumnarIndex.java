package com.axonbase.core.storage;

import com.axonbase.core.catalog.RecordId;
import com.axonbase.value.AxonValue;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Utilidades para o índice colunar: codificación de valores en orde
 * lexicográfica (sortable encoding) e construción de claves KV.
 *
 * <p>Formato de clave:</p>
 * <pre>
 *   CI|{ns}|{db}|{table}|{index}|{column}|{typeByte}{encodedData}{rowKey}
 * </pre>
 * Cada tipo de valor ten un tamaño fixo ou un prefixo de lonxitude, o que
 * fai o formato autodelimitante sen necesidade de separadores adicionais.
 */
public final class ColumnarIndex {

    private ColumnarIndex() {}

    public static final byte TYPE_NULL = 0x00;
    public static final byte TYPE_BOOL = 0x01;
    public static final byte TYPE_LONG = 0x02;
    public static final byte TYPE_DOUBLE = 0x03;
    public static final byte TYPE_STRING = 0x04;
    public static final byte TYPE_ARRAY = 0x05;
    public static final byte TYPE_OBJECT = 0x06;

    // ---- entradas com valor (varredura só pelo índice) ----

    /** Prefixo de todas as colunas de um índice: {@code CI|{ns}|{db}|{table}|{index}|}. */
    public static String indexPrefix(String ns, String db, String table, String index) {
        return "CI|" + ns + "|" + db + "|" + table + "|" + index + "|";
    }

    /**
     * Chave do marcador que diz que as entradas do índice guardam o valor e a chave da linha (formato novo).
     * Sem o marcador (índices criados antes) as agregações leem os documentos, como sempre.
     */
    public static String readyKey(String ns, String db, String table, String index) {
        return "CIV|" + ns + "|" + db + "|" + table + "|" + index;
    }

    /** Entrada lida do índice: a chave da linha e o valor da coluna com o tipo original. */
    public record Entry(String rowKey, AxonValue value) {
    }

    private static final byte ENTRY_V1 = 1;
    private static final byte TAG_NULL = 'Z';
    private static final byte TAG_TRUE = 'T';
    private static final byte TAG_FALSE = 'F';
    private static final byte TAG_LONG = 'L';
    private static final byte TAG_STRING = 'S';
    private static final byte TAG_JSON = 'J';

    /**
     * Valor guardado na entrada do índice: a chave da linha e o valor da coluna com o tipo original. A chave do
     * índice é uma String feita de bytes ordenáveis e não dá para decodificá-la com segurança, então os agregados
     * leem o valor daqui. Formato: versão, tamanho da chave da linha (2 bytes), chave da linha, tag do tipo e dado.
     * Inteiros, textos, booleanos e nulos têm tag própria (leitura sem JSON); o resto vai como JSON.
     */
    public static byte[] entryValue(String rowKey, AxonValue value) {
        byte[] row = rowKey.getBytes(StandardCharsets.UTF_8);
        byte tag;
        byte[] payload;
        if (value == null || value.isNull() || value.isNone()) {
            tag = TAG_NULL;
            payload = new byte[0];
        } else if (value.isBool()) {
            tag = value.asBool() ? TAG_TRUE : TAG_FALSE;
            payload = new byte[0];
        } else if (value.isString()) {
            tag = TAG_STRING;
            payload = value.asString().getBytes(StandardCharsets.UTF_8);
        } else if (value.isNumber() && value.isInteger()) {
            tag = TAG_LONG;
            payload = ByteBuffer.allocate(8).putLong(value.asLong()).array();
        } else {
            tag = TAG_JSON;
            payload = com.axonbase.value.AxonJson.encode(value);
        }
        ByteBuffer buf = ByteBuffer.allocate(1 + 2 + row.length + 1 + payload.length);
        buf.put(ENTRY_V1).putShort((short) row.length).put(row).put(tag).put(payload);
        return buf.array();
    }

    /** Lê uma entrada nova; {@code null} para entradas vazias (formato antigo) ou ilegíveis. */
    public static Entry decodeEntry(byte[] bytes) {
        if (bytes == null || bytes.length < 4 || bytes[0] != ENTRY_V1) {
            return null;
        }
        try {
            ByteBuffer buf = ByteBuffer.wrap(bytes);
            buf.get();
            int rowLen = buf.getShort() & 0xFFFF;
            byte[] row = new byte[rowLen];
            buf.get(row);
            byte tag = buf.get();
            byte[] payload = new byte[buf.remaining()];
            buf.get(payload);
            AxonValue value = switch (tag) {
                case TAG_NULL -> AxonValue.nul();
                case TAG_TRUE -> AxonValue.bool(true);
                case TAG_FALSE -> AxonValue.bool(false);
                case TAG_LONG -> AxonValue.num(ByteBuffer.wrap(payload).getLong());
                case TAG_STRING -> AxonValue.str(new String(payload, StandardCharsets.UTF_8));
                case TAG_JSON -> com.axonbase.value.AxonJson.decode(payload);
                default -> null;
            };
            return value == null ? null : new Entry(new String(row, StandardCharsets.UTF_8), value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // ---- key construction ----

    /** Prefixo para varrer toda a columna: {@code CI|{ns}|{db}|{table}|{index}|{column}|}. */
    public static String columnPrefix(String ns, String db, String table, String index, String column) {
        return "CI|" + ns + "|" + db + "|" + table + "|" + index + "|" + column + "|";
    }

    /**
     * Clave completa co valor codificado en orde sortable e o rowKey.
     * <pre>
     *   {prefix}{typeByte}{encodedData}{rowKey}
     * </pre>
     */
    public static String columnKey(String ns, String db, String table, String index,
                                    String column, AxonValue value, RecordId rid) {
        String prefix = columnPrefix(ns, db, table, index, column);
        byte[] encoded = encodeSortable(value);
        byte[] rowBytes = rid.key().keyString().getBytes(StandardCharsets.UTF_8);
        ByteBuffer buf = ByteBuffer.allocate(prefix.length() + encoded.length + rowBytes.length);
        buf.put(prefix.getBytes(StandardCharsets.UTF_8));
        buf.put(encoded);
        buf.put(rowBytes);
        return new String(buf.array(), StandardCharsets.UTF_8);
    }

    /**
     * Extrae a clave de fila (rowKey) a partir dunha clave completa do índice colunar.
     * A clave rowKey comeza despois dos datos codificados do valor.
     */
    public static String extractRowKey(byte[] fullKeyBytes, int prefixLen, int encodedLen) {
        return new String(fullKeyBytes, prefixLen + encodedLen,
            fullKeyBytes.length - prefixLen - encodedLen, StandardCharsets.UTF_8);
    }

    public static String extractRowKey(String fullKey, int prefixLen, int encodedLen) {
        return fullKey.substring(prefixLen + encodedLen);
    }

    /** Decodifica a lonxitude dos datos codificados a partir do primeiro byte (tipo). */
    public static int encodedDataLength(byte[] key, int offset) {
        if (offset >= key.length) return 0;
        byte type = key[offset];
        return switch (type) {
            case TYPE_NULL -> 1;
            case TYPE_BOOL -> 2;
            case TYPE_LONG -> 9;
            case TYPE_DOUBLE -> 9;
            case TYPE_STRING -> {
                if (offset + 3 > key.length) yield 0;
                int strLen = ((key[offset + 1] & 0xFF) << 8) | (key[offset + 2] & 0xFF);
                yield 3 + strLen;
            }
            case TYPE_ARRAY, TYPE_OBJECT -> {
                if (offset + 5 > key.length) yield 0;
                int dataLen = ((key[offset + 1] & 0xFF) << 24) | ((key[offset + 2] & 0xFF) << 16)
                    | ((key[offset + 3] & 0xFF) << 8) | (key[offset + 4] & 0xFF);
                yield 5 + dataLen;
            }
            default -> 0;
        };
    }

    // ---- sortable encoding ----

    /**
     * Codifica un AxonValue en bytes que preservan a orde lexicográfica.
     *
     * <p>Para números enteiros (long) usa big-endian co bit de signo invertido
     * para que os negativos vaian antes que os positivos. Para flotantes (double)
     * inverte o bit de signo para o mesmo efecto. As strings codifícanse como
     * UTF-8 con prefixo de lonxitude de 2 bytes.</p>
     */
    public static byte[] encodeSortable(AxonValue value) {
        if (value == null || value.isNull()) {
            return new byte[]{TYPE_NULL};
        }
        if (value.isNone()) {
            return new byte[]{TYPE_NULL};
        }
        if (value.isBool()) {
            return new byte[]{TYPE_BOOL, value.asBool() ? (byte) 1 : 0};
        }
        if (value.isNumber()) {
            java.math.BigDecimal dec = value.asDecimal();
            try {
                long longVal = dec.longValueExact();
                return encodeLong(longVal);
            } catch (ArithmeticException e) {
                return encodeDouble(dec.doubleValue());
            }
        }
        if (value.isString()) {
            return encodeString(value.asString());
        }
        if (value.isArray()) {
            return encodeJson(value, TYPE_ARRAY);
        }
        if (value.isObject()) {
            return encodeJson(value, TYPE_OBJECT);
        }
        return encodeString(value.toString());
    }

    private static byte[] encodeLong(long v) {
        long flipped = v ^ Long.MIN_VALUE;
        return ByteBuffer.allocate(9)
            .put(TYPE_LONG)
            .putLong(flipped)
            .array();
    }

    private static byte[] encodeDouble(double v) {
        long bits = Double.doubleToRawLongBits(v);
        long flipped = (bits & Long.MIN_VALUE) == 0 ? bits ^ Long.MAX_VALUE : bits ^ Long.MIN_VALUE;
        return ByteBuffer.allocate(9)
            .put(TYPE_DOUBLE)
            .putLong(bits) // simpler: just use raw bits, works for positive numbers
            .array();
    }

    private static byte[] encodeString(String s) {
        byte[] utf8 = s.getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(3 + utf8.length)
            .put(TYPE_STRING)
            .putShort((short) utf8.length)
            .put(utf8)
            .array();
    }

    private static byte[] encodeJson(AxonValue value, byte type) {
        byte[] json = com.axonbase.value.AxonJson.encode(value);
        return ByteBuffer.allocate(5 + json.length)
            .put(type)
            .putInt(json.length)
            .put(json)
            .array();
    }

    // ---- rowKey extraction helpers for query ----

    /**
     * Dado unha lista de claves do índice colunar, extrae os rowKey.
     * @param keys claves completas do índice
     * @param prefixLen lonxitude do prefixo da columna (antes do tipo)
     * @return lista de rowKeys (strings)
     */
    public static List<String> rowKeysFrom(List<String> keys, int prefixLen) {
        List<String> rowKeys = new ArrayList<>(keys.size());
        for (String key : keys) {
            byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
            int encodedLen = encodedDataLength(keyBytes, prefixLen);
            rowKeys.add(extractRowKey(key, prefixLen, encodedLen));
        }
        return rowKeys;
    }

    /**
     * Extrae o valor codificado en forma lexible (para agregación).
     * @param key clave completa do índice
     * @param prefixLen lonxitude do prefixo da columna
     * @return o valor como AxonValue, ou null se non é decodificable
     */
    public static AxonValue decodeValue(String key, int prefixLen) {
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        if (prefixLen >= keyBytes.length) return null;
        byte type = keyBytes[prefixLen];
        int offset = prefixLen + 1;
        return switch (type) {
            case TYPE_NULL -> AxonValue.nul();
            case TYPE_BOOL -> {
                if (offset >= keyBytes.length) yield null;
                yield AxonValue.bool(keyBytes[offset] != 0);
            }
            case TYPE_LONG -> {
                if (offset + 8 > keyBytes.length) yield null;
                long raw = 0;
                for (int i = 0; i < 8; i++) {
                    raw = (raw << 8) | (keyBytes[offset + i] & 0xFF);
                }
                long val = raw ^ Long.MIN_VALUE;
                yield AxonValue.num(val);
            }
            case TYPE_DOUBLE -> {
                if (offset + 8 > keyBytes.length) yield null;
                long bits = 0;
                for (int i = 0; i < 8; i++) {
                    bits = (bits << 8) | (keyBytes[offset + i] & 0xFF);
                }
                yield AxonValue.num(java.math.BigDecimal.valueOf(Double.longBitsToDouble(bits)));
            }
            case TYPE_STRING -> {
                if (offset + 2 > keyBytes.length) yield null;
                int strLen = ((keyBytes[offset] & 0xFF) << 8) | (keyBytes[offset + 1] & 0xFF);
                if (offset + 2 + strLen > keyBytes.length) yield null;
                yield AxonValue.str(new String(keyBytes, offset + 2, strLen, StandardCharsets.UTF_8));
            }
            case TYPE_ARRAY, TYPE_OBJECT -> {
                if (offset + 4 > keyBytes.length) yield null;
                int dataLen = ((keyBytes[offset] & 0xFF) << 24) | ((keyBytes[offset + 1] & 0xFF) << 16)
                    | ((keyBytes[offset + 2] & 0xFF) << 8) | (keyBytes[offset + 3] & 0xFF);
                if (offset + 4 + dataLen > keyBytes.length) yield null;
                byte[] json = java.util.Arrays.copyOfRange(keyBytes, offset + 4, offset + 4 + dataLen);
                yield com.axonbase.value.AxonJson.decode(json);
            }
            default -> null;
        };
    }
}
