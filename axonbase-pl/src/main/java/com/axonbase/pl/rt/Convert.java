package com.axonbase.pl.rt;

import com.axonbase.value.AxonValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Conversao entre AxonValue (fronteira) e os valores internos do AxonPL. */
public final class Convert {
    private Convert() {
    }

    /** AxonValue para valor interno. */
    public static Object deAxon(AxonValue v) {
        if (v == null) {
            return NoneType.NONE;
        }
        switch (v.type()) {
            case NONE:
                return NoneType.ABSENT;
            case NULL:
                return NoneType.NONE;
            case BOOL:
                return v.asBool();
            case NUMBER: {
                Object raw = v.raw();
                if (raw instanceof Long l) {
                    return l;
                }
                if (raw instanceof Double d) {
                    return d;
                }
                if (raw instanceof BigDecimal b) {
                    return b;
                }
                Number n = (Number) raw;
                if (n instanceof Integer || n instanceof Short || n instanceof Byte) {
                    return n.longValue();
                }
                return n.doubleValue();
            }
            case STRING:
                return v.asString();
            case DURATION:
                return v.asDuration();
            case DATETIME:
                return v.asInstant();
            case UUID:
                return v.asUuidValue();
            case ARRAY: {
                List<AxonValue> src = v.asArray();
                PyList l = new PyList(src.size());
                for (AxonValue x : src) {
                    l.a.add(deAxon(x));
                }
                return l;
            }
            case SET: {
                PySet s = new PySet(false);
                for (AxonValue x : v.asSet()) {
                    s.s.add(deAxon(x));
                }
                return s;
            }
            case OBJECT: {
                PyDict d = new PyDict();
                for (Map.Entry<String, AxonValue> e : v.asObject().entrySet()) {
                    d.m.put(e.getKey(), deAxon(e.getValue()));
                }
                return d;
            }
            case BYTES:
                return new PyBytes((byte[]) v.raw());
            case RECORD_ID:
                return v.asRecordId();
            default:
                return v;
        }
    }

    /** Valor interno para AxonValue; falha para funcoes e outros valores sem equivalente. */
    public static AxonValue paraAxon(Object o) {
        if (o instanceof String s) {
            return AxonValue.str(s);
        }
        if (o instanceof Long l) {
            return AxonValue.num(l.longValue());
        }
        if (o instanceof Boolean b) {
            return AxonValue.bool(b);
        }
        if (o instanceof Double d) {
            return AxonValue.num(d.doubleValue());
        }
        if (o instanceof NoneType n) {
            return n.absent ? AxonValue.none() : AxonValue.nul();
        }
        if (o instanceof BigDecimal b) {
            return AxonValue.num(b);
        }
        if (o instanceof PyList l) {
            List<AxonValue> r = new ArrayList<>(l.a.size());
            for (Object x : l.a) {
                r.add(paraAxon(x));
            }
            return AxonValue.array(r);
        }
        if (o instanceof PyTuple t) {
            List<AxonValue> r = new ArrayList<>(t.a.length);
            for (Object x : t.a) {
                r.add(paraAxon(x));
            }
            return AxonValue.array(r);
        }
        if (o instanceof PySet s) {
            List<AxonValue> r = new ArrayList<>(s.s.size());
            for (Object x : s.s) {
                r.add(paraAxon(x));
            }
            return AxonValue.set(r);
        }
        if (o instanceof PyDict d) {
            Map<String, AxonValue> r = new LinkedHashMap<>();
            for (Map.Entry<Object, Object> e : d.m.entrySet()) {
                if (!(e.getKey() instanceof String k)) {
                    throw Errors.type("so dicionarios com chaves texto podem virar objeto: chave do tipo '"
                            + Types.nomeDe(e.getKey()) + "'");
                }
                r.put(k, paraAxon(e.getValue()));
            }
            return AxonValue.object(r);
        }
        if (o instanceof Instant i) {
            return AxonValue.datetime(i);
        }
        if (o instanceof AxonValue.Duration d) {
            return AxonValue.duration(d.millis());
        }
        if (o instanceof AxonValue.RecordId r) {
            return AxonValue.record(r.table(), r.key());
        }
        if (o instanceof UUID u) {
            return AxonValue.uuid(u);
        }
        if (o instanceof PyBytes b) {
            return AxonValue.bytes(b.b);
        }
        if (o instanceof AxonValue a) {
            return a;
        }
        if (o instanceof PyRange r) {
            List<AxonValue> res = new ArrayList<>();
            for (long i = 0; i < r.size(); i++) {
                res.add(AxonValue.num(r.at(i)));
            }
            return AxonValue.array(res);
        }
        throw Errors.type("o valor do tipo '" + Types.nomeDe(o) + "' nao pode ser convertido para AxonValue");
    }

    /** Como paraAxon, mas valores sem equivalente viram texto (para depuracao). */
    public static AxonValue paraAxonSolto(Object o) {
        try {
            return paraAxon(o);
        } catch (RuntimeException e) {
            return AxonValue.str(Repr.repr(o));
        }
    }
}
