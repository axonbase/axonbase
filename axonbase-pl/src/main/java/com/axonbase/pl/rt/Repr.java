package com.axonbase.pl.rt;

import com.axonbase.value.AxonValue;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

/** repr() e str() dos valores do AxonPL, fieis ao Python. */
public final class Repr {
    private Repr() {
    }

    public static String str(Object o) {
        if (o instanceof String s) {
            return s;
        }
        if (o instanceof PyException e) {
            return e.mensagem();
        }
        if (o instanceof BigDecimal d) {
            return d.toString();
        }
        if (o instanceof AxonValue.RecordId r) {
            return r.table() + ":" + keyStr(r.key());
        }
        return repr(o, 0);
    }

    public static String repr(Object o) {
        return repr(o, 0);
    }

    static String repr(Object o, int depth) {
        if (depth > 40) {
            return "...";
        }
        if (o instanceof Long l) {
            return Long.toString(l);
        }
        if (o instanceof String s) {
            return quote(s);
        }
        if (o instanceof Boolean b) {
            return b ? "True" : "False";
        }
        if (o instanceof Double d) {
            return floatRepr(d);
        }
        if (o instanceof NoneType) {
            return "None";
        }
        if (o instanceof PyList l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.a.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(repr(l.a.get(i), depth + 1));
            }
            return sb.append(']').toString();
        }
        if (o instanceof PyTuple t) {
            StringBuilder sb = new StringBuilder("(");
            for (int i = 0; i < t.a.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(repr(t.a[i], depth + 1));
            }
            if (t.a.length == 1) {
                sb.append(',');
            }
            return sb.append(')').toString();
        }
        if (o instanceof PyDict d) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<Object, Object> e : d.m.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(repr(e.getKey(), depth + 1)).append(": ").append(repr(e.getValue(), depth + 1));
            }
            return sb.append('}').toString();
        }
        if (o instanceof PySet s) {
            if (s.s.isEmpty()) {
                return s.frozen ? "frozenset()" : "set()";
            }
            StringBuilder sb = new StringBuilder(s.frozen ? "frozenset({" : "{");
            boolean first = true;
            for (Object x : s.s) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(repr(x, depth + 1));
            }
            return sb.append(s.frozen ? "})" : "}").toString();
        }
        if (o instanceof BigDecimal d) {
            return "Decimal('" + d.toString() + "')";
        }
        if (o instanceof PyException e) {
            StringBuilder sb = new StringBuilder(e.type.name).append('(');
            for (int i = 0; i < e.args.length; i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(repr(e.args[i], depth + 1));
            }
            return sb.append(')').toString();
        }
        if (o instanceof PyRange r) {
            return r.step == 1 ? "range(" + r.start + ", " + r.stop + ")"
                    : "range(" + r.start + ", " + r.stop + ", " + r.step + ")";
        }
        if (o instanceof PyBytes b) {
            return bytesRepr(b.b);
        }
        if (o instanceof PyType t) {
            return "<class '" + t.name + "'>";
        }
        if (o instanceof Builtin b) {
            return "<built-in function " + b.nome() + ">";
        }
        if (o instanceof PyCallable c) {
            return "<function " + c.nome() + ">";
        }
        if (o instanceof PyModule m) {
            return "<module '" + m.nome + "'>";
        }
        if (o instanceof HostModule m) {
            return "<module '" + m.nome + "'>";
        }
        if (o instanceof Instant i) {
            return i.toString();
        }
        if (o instanceof AxonValue.Duration d) {
            return d.toString();
        }
        if (o instanceof AxonValue.RecordId r) {
            return "rid(" + quote(r.table()) + ", " + keyRepr(r.key()) + ")";
        }
        if (o instanceof PyIterator) {
            return "<iterator>";
        }
        if (o instanceof PySlice s) {
            return "slice(" + repr(s.lo, depth + 1) + ", " + repr(s.hi, depth + 1) + ", " + repr(s.step, depth + 1) + ")";
        }
        if (o instanceof java.util.UUID u) {
            return "uuid('" + u + "')";
        }
        return String.valueOf(o);
    }

    /** Parte da chave de um record, como aparece em str. */
    public static String keyStr(Object key) {
        Object k = key instanceof AxonValue v ? Convert.deAxon(v) : key;
        return k instanceof String s ? s : str(k);
    }

    private static String keyRepr(Object key) {
        Object k = key instanceof AxonValue v ? Convert.deAxon(v) : key;
        return repr(k);
    }

    public static String quote(String s) {
        char q = s.indexOf('\'') >= 0 && s.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append(q);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == q || c == '\\') {
                sb.append('\\').append(c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 32 || c == 127) {
                sb.append(String.format("\\x%02x", (int) c));
            } else if (c < 127 || printable(c)) {
                sb.append(c);
            } else if (c < 256) {
                sb.append(String.format("\\x%02x", (int) c));
            } else {
                sb.append(String.format("\\u%04x", (int) c));
            }
        }
        return sb.append(q).toString();
    }

    private static boolean printable(char c) {
        if (Character.isSurrogate(c)) {
            return true;
        }
        return switch (Character.getType(c)) {
            case Character.CONTROL, Character.FORMAT, Character.PRIVATE_USE, Character.UNASSIGNED,
                    Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SPACE_SEPARATOR -> false;
            default -> true;
        };
    }

    static String bytesRepr(byte[] b) {
        StringBuilder sb = new StringBuilder("b'");
        for (byte x : b) {
            int c = x & 0xff;
            if (c == '\'' || c == '\\') {
                sb.append('\\').append((char) c);
            } else if (c == '\n') {
                sb.append("\\n");
            } else if (c == '\r') {
                sb.append("\\r");
            } else if (c == '\t') {
                sb.append("\\t");
            } else if (c < 32 || c >= 127) {
                sb.append(String.format("\\x%02x", c));
            } else {
                sb.append((char) c);
            }
        }
        return sb.append('\'').toString();
    }

    /** Representacao de float igual a do Python (menor string que reproduz o valor). */
    public static String floatRepr(double d) {
        if (Double.isNaN(d)) {
            return "nan";
        }
        if (Double.isInfinite(d)) {
            return d > 0 ? "inf" : "-inf";
        }
        if (d == 0) {
            return 1 / d < 0 ? "-0.0" : "0.0";
        }
        BigDecimal bd = new BigDecimal(Double.toString(Math.abs(d))).stripTrailingZeros();
        if (bd.precision() == 2) {
            BigDecimal um = bd.round(new java.math.MathContext(1, java.math.RoundingMode.HALF_EVEN));
            if (um.doubleValue() == Math.abs(d)) {
                bd = um.stripTrailingZeros();
            }
        }
        String ds = bd.unscaledValue().toString();
        int sc = bd.scale();
        int exp10 = ds.length() - sc - 1;
        StringBuilder sb = new StringBuilder();
        if (d < 0) {
            sb.append('-');
        }
        if (exp10 >= -4 && exp10 < 16) {
            if (sc <= 0) {
                sb.append(ds).append("0".repeat(-sc)).append(".0");
            } else if (ds.length() > sc) {
                sb.append(ds, 0, ds.length() - sc).append('.').append(ds, ds.length() - sc, ds.length());
            } else {
                sb.append("0.").append("0".repeat(sc - ds.length())).append(ds);
            }
        } else {
            sb.append(ds.charAt(0));
            if (ds.length() > 1) {
                sb.append('.').append(ds, 1, ds.length());
            }
            sb.append('e').append(exp10 < 0 ? '-' : '+');
            int ae = Math.abs(exp10);
            if (ae < 10) {
                sb.append('0');
            }
            sb.append(ae);
        }
        return sb.toString();
    }
}
