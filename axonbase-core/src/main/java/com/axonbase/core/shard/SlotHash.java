package com.axonbase.core.shard;

import com.axonbase.value.AxonValue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;

/**
 * Slot de um valor de chave de shard: FNV-1a (Fowler-Noll-Vo) de 64 bits sobre a forma de texto do valor, com uma
 * mistura final, em {@value #SLOTS} slots. O resultado precisa ser o mesmo em qualquer versão do Java e em qualquer
 * nó, porque o gateway roteia por ele e o shard filtra por ele.
 */
public final class SlotHash {

    /** Quantidade fixa de slots; o mapa dos shards atribui cada um a um shard. */
    public static final int SLOTS = 4096;

    private static final long FNV_OFFSET = 0xcbf29ce484222325L;
    private static final long FNV_PRIME = 0x100000001b3L;
    private static final int MAX_NUMERIC_TEXT = 64;
    private static final int MAX_NUMERIC_SCALE = 1000;

    private SlotHash() {
    }

    /** Slot de um valor de campo. */
    public static int ofValue(AxonValue value) {
        return ofText(text(value));
    }

    /**
     * Slot de um {@code id} numa tabela com {@code SHARD BY id}: vale a parte da chave. O {@code id} guardado volta do
     * armazenamento como o texto {@code tabela:chave}.
     */
    public static int ofId(String table, AxonValue id) {
        return ofText(idText(table, id));
    }

    static String idText(String table, AxonValue id) {
        if (id != null && id.isRecordId()) {
            Object key = id.asRecordId().key();
            return key instanceof AxonValue value ? text(value) : String.valueOf(key);
        }
        if (id != null && id.isString() && id.asString().startsWith(table + ":")) {
            return id.asString().substring(table.length() + 1);
        }
        return text(id);
    }

    /**
     * O motor trata número e texto numérico como iguais ({@code 7 = "007"}): os dois precisam cair no mesmo slot, então
     * o texto numérico é reduzido à forma decimal.
     */
    private static String canonical(String text) {
        if (text.isEmpty() || text.length() > MAX_NUMERIC_TEXT) {
            return text;
        }
        char first = text.charAt(0);
        if (!Character.isDigit(first) && first != '-' && first != '+' && first != '.') {
            return text;
        }
        try {
            BigDecimal number = new BigDecimal(text);
            // um expoente enorme viraria um texto de milhões de zeros
            return Math.abs((long) number.scale()) > MAX_NUMERIC_SCALE ? text
                : number.stripTrailingZeros().toPlainString();
        } catch (NumberFormatException notANumber) {
            return text;
        }
    }

    static int ofText(String raw) {
        String text = canonical(raw);
        long hash = FNV_OFFSET;
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            hash ^= b & 0xff;
            hash *= FNV_PRIME;
        }
        // mistura final: sem ela os bits baixos de textos quase iguais ficam correlacionados
        hash ^= hash >>> 33;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= hash >>> 33;
        hash *= 0xc4ceb9fe1a85ec53L;
        hash ^= hash >>> 33;
        return (int) Math.floorMod(hash, (long) SLOTS);
    }

    private static String text(AxonValue value) {
        if (value == null) {
            throw ShardException.refused("shard_key_missing");
        }
        return switch (value.type()) {
            case NUMBER -> value.asDecimal().stripTrailingZeros().toPlainString();
            case STRING -> value.asString();
            case UUID -> value.asUuidValue().toString();
            case RECORD_ID -> value.asRecordId().toString();
            case BOOL -> Boolean.toString(value.asBool());
            case DATETIME -> value.asInstant().toString();
            default -> throw ShardException.refused("shard_key_invalid", value.type().name());
        };
    }
}
