package com.axonbase.pl.rt;

import com.axonbase.pl.PlCallable;
import com.axonbase.value.AxonValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Map;

/** Acesso a atributos e metodos. Atributos especiais (dunder) nunca existem. */
public final class Attrs {
    private Attrs() {
    }

    /** Tabela de metodos do tipo do objeto, ou null. */
    public static Method method(Object o, String name) {
        Map<String, Method> t;
        if (o instanceof String) {
            t = StrMethods.T;
        } else if (o instanceof PyList) {
            t = ListMethods.L;
        } else if (o instanceof PyDict) {
            t = DictMethods.D;
        } else if (o instanceof PySet) {
            t = DictMethods.S;
        } else if (o instanceof PyTuple) {
            t = ListMethods.TUP;
        } else if (o instanceof Long || o instanceof Boolean) {
            t = MiscMethods.INT;
        } else if (o instanceof Double) {
            t = MiscMethods.FLOAT;
        } else if (o instanceof BigDecimal) {
            t = MiscMethods.DEC;
        } else if (o instanceof Instant) {
            t = MiscMethods.INSTANT;
        } else if (o instanceof AxonValue.Duration) {
            t = MiscMethods.DURATION;
        } else if (o instanceof PyBytes) {
            t = MiscMethods.BYTES;
        } else {
            return null;
        }
        return t.get(name);
    }

    public static Object getattr(Frame f, Object o, String name) {
        if (name.length() > 4 && name.startsWith("__") && name.endsWith("__")) {
            throw Errors.attr("o atributo especial '" + name + "' nao esta disponivel no AxonPL");
        }
        if (o instanceof PyModule m) {
            Object v = m.attrs.get(name);
            if (v == null) {
                throw Errors.attr("o modulo '" + m.nome + "' nao tem o atributo '" + name + "'");
            }
            return v;
        }
        if (o instanceof HostModule h) {
            Object r = f.ctx.host == null ? null : f.ctx.host.attr(h.nome, name);
            if (r == null) {
                throw Errors.attr("o modulo '" + h.nome + "' nao tem o atributo '" + name + "'");
            }
            if (r instanceof PlCallable c) {
                return new HostFn(c, h.nome, name);
            }
            if (r instanceof AxonValue v) {
                return Convert.deAxon(v);
            }
            throw Errors.type("o host devolveu um atributo de tipo invalido para " + h.nome + "." + name);
        }
        if (o instanceof PyException e && name.equals("args")) {
            return new PyTuple(e.args.clone());
        }
        if (o instanceof Instant t) {
            ZonedDateTime z = t.atZone(ZoneOffset.UTC);
            switch (name) {
                case "year":
                    return (long) z.getYear();
                case "month":
                    return (long) z.getMonthValue();
                case "day":
                    return (long) z.getDayOfMonth();
                case "hour":
                    return (long) z.getHour();
                case "minute":
                    return (long) z.getMinute();
                case "second":
                    return (long) z.getSecond();
                case "millisecond":
                    return (long) (z.getNano() / 1_000_000);
                default:
                    break;
            }
        }
        if (o instanceof AxonValue.Duration d && name.equals("millis")) {
            return d.millis();
        }
        if (o instanceof AxonValue.RecordId r) {
            if (name.equals("table")) {
                return r.table();
            }
            if (name.equals("key")) {
                Object k = r.key();
                return k instanceof AxonValue v ? Convert.deAxon(v) : k instanceof Number n && !(k instanceof Long)
                        ? (Object) n.longValue() : k;
            }
        }
        Method m = method(o, name);
        if (m != null) {
            return new BoundMethod(o, m, name);
        }
        throw Errors.attr("o objeto do tipo '" + Types.nomeDe(o) + "' nao tem o atributo '" + name + "'");
    }
}
