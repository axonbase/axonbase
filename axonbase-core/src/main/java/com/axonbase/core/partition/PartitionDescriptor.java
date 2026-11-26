package com.axonbase.core.partition;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uma partição: a faixa {@code [start, end)} em milissegundos, o estado e o objeto frio. Em {@code COLD} e
 * {@code THAWING} o objeto guarda os registros; em {@code FREEZING} é o nome reservado para o envio em curso.
 *
 * @param pinned alguém pediu para ela ficar local: o prazo de {@code COLD AFTER} não a leva de volta
 */
public record PartitionDescriptor(long start, long end, State state, String object, long rows, long bytes,
                                  boolean pinned) {

    public enum State {
        /** Local, lê e grava. */
        HOT,
        /** Indo para a camada fria: local, só leitura. */
        FREEZING,
        /** Registros no armazenamento de objetos, só leitura. */
        COLD,
        /** Voltando da camada fria: lida do objeto, só leitura. */
        THAWING
    }

    public static PartitionDescriptor hot(long start, long end) {
        return new PartitionDescriptor(start, end, State.HOT, null, 0, 0, false);
    }

    /** Pede a partição na camada fria; a mudança em si é feita em segundo plano. */
    public PartitionDescriptor requestCold() {
        return switch (state) {
            case HOT -> new PartitionDescriptor(start, end, State.FREEZING, null, 0, 0, false);
            case THAWING -> new PartitionDescriptor(start, end, State.COLD, object, rows, bytes, false);
            case FREEZING, COLD -> new PartitionDescriptor(start, end, state, object, rows, bytes, false);
        };
    }

    /** Pede a partição local: uma congelada volta na hora, uma fria é trazida em segundo plano. */
    public PartitionDescriptor requestHot() {
        return switch (state) {
            case HOT, FREEZING -> new PartitionDescriptor(start, end, State.HOT, null, 0, 0, true);
            case COLD, THAWING -> new PartitionDescriptor(start, end, State.THAWING, object, rows, bytes, true);
        };
    }

    /** De volta ao armazenamento local, com os registros já gravados. */
    public PartitionDescriptor thawed() {
        return new PartitionDescriptor(start, end, State.HOT, null, 0, 0, pinned);
    }

    /** Congelada, com o nome reservado para o objeto que vai ser enviado. */
    public PartitionDescriptor freezing(String reservedKey) {
        return new PartitionDescriptor(start, end, State.FREEZING, reservedKey, 0, 0, pinned);
    }

    /** A partição com os registros no objeto dado. */
    public PartitionDescriptor cold(String objectKey, long rowCount, long size) {
        return new PartitionDescriptor(start, end, State.COLD, objectKey, rowCount, size, pinned);
    }

    public boolean writable() {
        return state == State.HOT;
    }

    /** {@code true} quando os registros estão no objeto frio, e não em chaves locais. */
    public boolean coldData() {
        return state == State.COLD || state == State.THAWING;
    }

    public byte[] encode() {
        Map<String, AxonValue> fields = new LinkedHashMap<>();
        fields.put("start", AxonValue.num(start));
        fields.put("end", AxonValue.num(end));
        fields.put("state", AxonValue.str(state.name()));
        if (pinned) {
            fields.put("pinned", AxonValue.bool(true));
        }
        if (object != null) {
            fields.put("object", AxonValue.str(object));
            fields.put("rows", AxonValue.num(rows));
            fields.put("bytes", AxonValue.num(bytes));
        }
        return AxonJson.encode(AxonValue.object(fields));
    }

    public static PartitionDescriptor decode(byte[] raw) {
        Map<String, AxonValue> fields = AxonJson.decode(raw).asObject();
        AxonValue object = fields.get("object");
        return new PartitionDescriptor(fields.get("start").asLong(), fields.get("end").asLong(),
            State.valueOf(fields.get("state").asString()), object == null ? null : object.asString(),
            object == null ? 0 : fields.get("rows").asLong(), object == null ? 0 : fields.get("bytes").asLong(),
            fields.containsKey("pinned"));
    }
}
