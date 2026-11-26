package com.axonbase.core.partition;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Uma partição: a faixa {@code [start, end)} em milissegundos, o estado e, quando os registros estão na camada fria,
 * o objeto que os guarda.
 */
public record PartitionDescriptor(long start, long end, State state, String object, long rows, long bytes) {

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
        return new PartitionDescriptor(start, end, State.HOT, null, 0, 0);
    }

    public PartitionDescriptor with(State next) {
        return new PartitionDescriptor(start, end, next, object, rows, bytes);
    }

    /** A partição com os registros no objeto dado. */
    public PartitionDescriptor cold(String objectKey, long rowCount, long size) {
        return new PartitionDescriptor(start, end, State.COLD, objectKey, rowCount, size);
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
            object == null ? 0 : fields.get("rows").asLong(), object == null ? 0 : fields.get("bytes").asLong());
    }
}
