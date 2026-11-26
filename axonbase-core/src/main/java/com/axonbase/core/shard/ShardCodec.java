package com.axonbase.core.shard;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.value.AxonValue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Formato binário das mensagens entre gateway e shards. Ao contrário do JSON (JavaScript Object Notation), preserva
 * todos os tipos, que a fusão das consultas precisa comparar como o motor compara.
 */
public final class ShardCodec {

    private static final int VERSION = 1;
    private static final int MAX_DEPTH = 200;

    private static final int NONE = 0;
    private static final int NULL = 1;
    private static final int BOOL = 2;
    private static final int LONG = 3;
    private static final int DOUBLE = 4;
    private static final int DECIMAL = 5;
    private static final int STRING = 6;
    private static final int DURATION = 7;
    private static final int DATETIME = 8;
    private static final int UUID_VALUE = 9;
    private static final int ARRAY = 10;
    private static final int SET = 11;
    private static final int OBJECT = 12;
    private static final int BYTES = 13;
    private static final int RECORD = 14;
    private static final int TABLE = 15;

    private ShardCodec() {
    }

    public static byte[] encode(ShardRequest request) {
        return write(out -> {
            optional(out, request.namespace());
            optional(out, request.database());
            text(out, request.sql());
            out.writeInt(request.vars().size());
            for (Map.Entry<String, AxonValue> var : request.vars().entrySet()) {
                text(out, var.getKey());
                value(out, var.getValue());
            }
            out.writeBoolean(request.atomic());
            out.writeBoolean(request.partial());
            SlotFilter filter = request.filter();
            out.writeBoolean(filter != null);
            if (filter != null) {
                text(out, filter.table());
                text(out, filter.field());
                text(out, SlotRange.format(filter.ranges()));
            }
            stamp(out, request.stamp());
        });
    }

    /** @param authorization o cabeçalho que veio na mensagem HTTP, que não vai no corpo */
    public static ShardRequest decodeRequest(byte[] bytes, String authorization) {
        return read(bytes, in -> {
            String namespace = readOptional(in);
            String database = readOptional(in);
            String sql = readText(in);
            int count = size(in);
            Map<String, AxonValue> vars = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String name = readText(in);
                vars.put(name, readValue(in, 0));
            }
            boolean atomic = in.readBoolean();
            boolean partial = in.readBoolean();
            SlotFilter filter = null;
            if (in.readBoolean()) {
                String table = readText(in);
                String field = readText(in);
                filter = new SlotFilter(table, field, SlotRange.parse(readText(in)));
            }
            return new ShardRequest(namespace, database, sql, vars, atomic, partial, filter, readStamp(in),
                authorization);
        });
    }

    public static byte[] encode(ShardReply reply) {
        return write(out -> {
            value(out, reply.value());
            stamp(out, reply.stamp());
        });
    }

    public static ShardReply decodeReply(byte[] bytes) {
        return read(bytes, in -> {
            AxonValue value = readValue(in, 0);
            return new ShardReply(value, readStamp(in));
        });
    }

    private interface Writer {
        void write(DataOutputStream out) throws IOException;
    }

    private interface Reader<T> {
        T read(DataInputStream in) throws IOException;
    }

    private static byte[] write(Writer writer) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(256);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.writeByte(VERSION);
            writer.write(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return bytes.toByteArray();
    }

    private static <T> T read(byte[] bytes, Reader<T> reader) {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            int version = in.readUnsignedByte();
            if (version != VERSION) {
                throw new IllegalArgumentException("versão de mensagem desconhecida: " + version);
            }
            T result = reader.read(in);
            if (in.available() != 0) {
                throw new IllegalArgumentException("mensagem com bytes sobrando");
            }
            return result;
        } catch (IOException e) {
            throw new IllegalArgumentException("mensagem truncada ou inválida", e);
        }
    }

    private static void stamp(DataOutputStream out, HlcTimestamp stamp) throws IOException {
        out.writeLong(stamp.physical());
        out.writeInt(stamp.logical());
    }

    private static HlcTimestamp readStamp(DataInputStream in) throws IOException {
        long physical = in.readLong();
        return new HlcTimestamp(physical, in.readInt());
    }

    private static void optional(DataOutputStream out, String text) throws IOException {
        out.writeBoolean(text != null);
        if (text != null) {
            text(out, text);
        }
    }

    private static String readOptional(DataInputStream in) throws IOException {
        return in.readBoolean() ? readText(in) : null;
    }

    private static void text(DataOutputStream out, String text) throws IOException {
        blob(out, text.getBytes(StandardCharsets.UTF_8));
    }

    private static String readText(DataInputStream in) throws IOException {
        return new String(readBlob(in), StandardCharsets.UTF_8);
    }

    private static void blob(DataOutputStream out, byte[] data) throws IOException {
        out.writeInt(data.length);
        out.write(data);
    }

    private static byte[] readBlob(DataInputStream in) throws IOException {
        byte[] data = new byte[size(in)];
        in.readFully(data);
        return data;
    }

    /** Um tamanho nunca passa do que resta na mensagem, então um valor forjado não reserva memória. */
    private static int size(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > in.available()) {
            throw new IllegalArgumentException("tamanho inválido na mensagem: " + size);
        }
        return size;
    }

    private static void value(DataOutputStream out, AxonValue value) throws IOException {
        switch (value.type()) {
            case NONE -> out.writeByte(NONE);
            case NULL -> out.writeByte(NULL);
            case BOOL -> {
                out.writeByte(BOOL);
                out.writeBoolean(value.asBool());
            }
            case NUMBER -> number(out, value.raw());
            case STRING -> {
                out.writeByte(STRING);
                text(out, value.asString());
            }
            case DURATION -> {
                out.writeByte(DURATION);
                out.writeLong(value.asDuration().millis());
            }
            case DATETIME -> {
                out.writeByte(DATETIME);
                out.writeLong(value.asInstant().getEpochSecond());
                out.writeInt(value.asInstant().getNano());
            }
            case UUID -> {
                out.writeByte(UUID_VALUE);
                out.writeLong(value.asUuidValue().getMostSignificantBits());
                out.writeLong(value.asUuidValue().getLeastSignificantBits());
            }
            case ARRAY -> list(out, ARRAY, value.asArray());
            case SET -> list(out, SET, value.asSet());
            case OBJECT -> {
                out.writeByte(OBJECT);
                Map<String, AxonValue> object = value.asObject();
                out.writeInt(object.size());
                for (Map.Entry<String, AxonValue> entry : object.entrySet()) {
                    text(out, entry.getKey());
                    value(out, entry.getValue());
                }
            }
            case BYTES -> {
                out.writeByte(BYTES);
                blob(out, (byte[]) value.raw());
            }
            case RECORD_ID -> {
                out.writeByte(RECORD);
                text(out, value.asRecordId().table());
                Object key = value.asRecordId().key();
                // a chave de um record id pode ser um valor ou o objeto cru que o armazenamento devolveu
                if (key instanceof AxonValue typed) {
                    out.writeByte(0);
                    value(out, typed);
                } else if (key instanceof Long number) {
                    out.writeByte(1);
                    out.writeLong(number);
                } else {
                    out.writeByte(2);
                    text(out, String.valueOf(key));
                }
            }
            case TABLE -> {
                out.writeByte(TABLE);
                text(out, value.asTable());
            }
        }
    }

    private static void number(DataOutputStream out, Object number) throws IOException {
        if (number instanceof BigDecimal decimal) {
            out.writeByte(DECIMAL);
            text(out, decimal.toString());
        } else if (number instanceof Double real) {
            out.writeByte(DOUBLE);
            out.writeDouble(real);
        } else {
            out.writeByte(LONG);
            out.writeLong(((Number) number).longValue());
        }
    }

    private static void list(DataOutputStream out, int tag, List<AxonValue> items) throws IOException {
        out.writeByte(tag);
        out.writeInt(items.size());
        for (AxonValue item : items) {
            value(out, item);
        }
    }

    private static AxonValue readValue(DataInputStream in, int depth) throws IOException {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("valor aninhado demais");
        }
        int tag = in.readUnsignedByte();
        return switch (tag) {
            case NONE -> AxonValue.none();
            case NULL -> AxonValue.nul();
            case BOOL -> AxonValue.bool(in.readBoolean());
            case LONG -> AxonValue.num(in.readLong());
            case DOUBLE -> AxonValue.num(in.readDouble());
            case DECIMAL -> AxonValue.num(new BigDecimal(readText(in)));
            case STRING -> AxonValue.str(readText(in));
            case DURATION -> AxonValue.duration(in.readLong());
            case DATETIME -> {
                long seconds = in.readLong();
                yield AxonValue.datetime(Instant.ofEpochSecond(seconds, in.readInt()));
            }
            case UUID_VALUE -> {
                long high = in.readLong();
                yield AxonValue.uuid(new UUID(high, in.readLong()));
            }
            case ARRAY -> AxonValue.array(readList(in, depth));
            case SET -> AxonValue.set(readList(in, depth));
            case OBJECT -> {
                int count = size(in);
                Map<String, AxonValue> object = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    String key = readText(in);
                    object.put(key, readValue(in, depth + 1));
                }
                yield AxonValue.object(object);
            }
            case BYTES -> AxonValue.bytes(readBlob(in));
            case RECORD -> {
                String table = readText(in);
                int kind = in.readUnsignedByte();
                Object key = switch (kind) {
                    case 0 -> readValue(in, depth + 1);
                    case 1 -> in.readLong();
                    case 2 -> readText(in);
                    default -> throw new IllegalArgumentException("chave de record id desconhecida: " + kind);
                };
                yield AxonValue.record(table, key);
            }
            case TABLE -> AxonValue.table(readText(in));
            default -> throw new IllegalArgumentException("tipo de valor desconhecido: " + tag);
        };
    }

    private static List<AxonValue> readList(DataInputStream in, int depth) throws IOException {
        int count = size(in);
        List<AxonValue> items = new ArrayList<>(Math.min(count, 1024));
        for (int i = 0; i < count; i++) {
            items.add(readValue(in, depth + 1));
        }
        return items;
    }
}
