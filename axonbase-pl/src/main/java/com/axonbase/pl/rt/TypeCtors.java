package com.axonbase.pl.rt;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** Construtores dos tipos embutidos (int, float, str, list, ...) e registro das excecoes. */
final class TypeCtors {
    private TypeCtors() {
    }

    private static void ctor(PyType t, Fn fn) {
        t.ctor = fn;
    }

    static Object toInt(Object x, Object base) {
        if (x instanceof Long || x instanceof Boolean) {
            return Ops.asLong(x);
        }
        if (x instanceof Double d) {
            if (Double.isNaN(d)) {
                throw Errors.value("nao e possivel converter NaN para inteiro");
            }
            if (Double.isInfinite(d)) {
                throw Errors.overflow("nao e possivel converter infinito para inteiro");
            }
            if (Math.abs(d) >= 9.2e18) {
                throw Errors.overflow64();
            }
            return (long) (double) d;
        }
        if (x instanceof BigDecimal b) {
            BigInteger bi = b.toBigInteger();
            if (bi.bitLength() > 63) {
                throw Errors.overflow64();
            }
            return bi.longValue();
        }
        if (x instanceof String s) {
            return NumParse.parseInt(s, base == null ? 10 : (int) Ops.index(base));
        }
        throw Errors.type("int() nao aceita um argumento do tipo '" + Types.nomeDe(x) + "'");
    }

    static BigDecimal toDecimal(Object x) {
        if (x instanceof BigDecimal b) {
            return b;
        }
        if (x instanceof Long || x instanceof Boolean) {
            return BigDecimal.valueOf(Ops.asLong(x));
        }
        if (x instanceof Double d) {
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw Errors.value("Decimal nao suporta NaN nem infinito no AxonPL");
            }
            return new BigDecimal(d);
        }
        if (x instanceof String s) {
            return NumParse.parseDecimal(s);
        }
        throw Errors.type("Decimal() nao aceita um argumento do tipo '" + Types.nomeDe(x) + "'");
    }

    static void install(Map<String, Object> table) {
        ctor(Types.INT, (f, a, kw) -> {
            Args.n("int", a, 0, 2);
            Args.onlyKw("int", kw, "base");
            if (a.length == 0) {
                return 0L;
            }
            Object base = a.length > 1 ? a[1] : Args.kw(kw, "base");
            if (base != null && !(a[0] instanceof String)) {
                throw Errors.type("int() so aceita base com argumento texto");
            }
            return toInt(a[0], base);
        });
        ctor(Types.BOOL, (f, a, kw) -> {
            Args.n("bool", a, 0, 1);
            return a.length > 0 && Ops.truthy(a[0]);
        });
        ctor(Types.FLOAT, (f, a, kw) -> {
            Args.n("float", a, 0, 1);
            if (a.length == 0) {
                return 0.0;
            }
            Object x = a[0];
            if (x instanceof String s) {
                return NumParse.parseFloat(s);
            }
            if (Ops.isNum(x)) {
                return Ops.toDouble(x);
            }
            throw Errors.type("float() nao aceita um argumento do tipo '" + Types.nomeDe(x) + "'");
        });
        ctor(Types.STR, (f, a, kw) -> {
            Args.n("str", a, 0, 1);
            return a.length == 0 ? "" : Repr.str(a[0]);
        });
        ctor(Types.LIST, (f, a, kw) -> {
            Args.n("list", a, 0, 1);
            return a.length == 0 ? new PyList() : new PyList(Ops.toList(f, a[0]));
        });
        ctor(Types.TUPLE, (f, a, kw) -> {
            Args.n("tuple", a, 0, 1);
            return a.length == 0 ? PyTuple.VAZIA : new PyTuple(Ops.toList(f, a[0]).toArray());
        });
        ctor(Types.SET, (f, a, kw) -> {
            Args.n("set", a, 0, 1);
            PySet s = new PySet(false);
            if (a.length > 0) {
                for (Object o : Ops.toList(f, a[0])) {
                    s.add(o);
                }
            }
            return s;
        });
        ctor(Types.FROZENSET, (f, a, kw) -> {
            Args.n("frozenset", a, 0, 1);
            PySet s = new PySet(true);
            if (a.length > 0) {
                for (Object o : Ops.toList(f, a[0])) {
                    s.add(o);
                }
            }
            return s;
        });
        ctor(Types.DICT, (f, a, kw) -> {
            Args.n("dict", a, 0, 1);
            PyDict d = new PyDict();
            if (a.length == 1) {
                if (a[0] instanceof PyDict src) {
                    d.m.putAll(src.m);
                } else {
                    for (Object item : Ops.toList(f, a[0])) {
                        List<Object> pair = Ops.toList(f, item);
                        if (pair.size() != 2) {
                            throw Errors.value("cada elemento de dict() deve ser um par chave/valor");
                        }
                        d.put(pair.get(0), pair.get(1));
                    }
                }
            }
            if (kw != null) {
                d.m.putAll(kw);
            }
            return d;
        });
        ctor(Types.BYTES, (f, a, kw) -> {
            Args.n("bytes", a, 0, 2);
            if (a.length == 0) {
                return new PyBytes(new byte[0]);
            }
            if (a[0] instanceof String s) {
                if (a.length < 2) {
                    throw Errors.type("bytes(str) exige o nome da codificacao, por exemplo 'utf-8'");
                }
                return new PyBytes(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            if (a[0] instanceof Long n) {
                f.ctx.checkElems(n);
                return new PyBytes(new byte[(int) (long) n]);
            }
            List<Object> items = Ops.toList(f, a[0]);
            byte[] b = new byte[items.size()];
            for (int i = 0; i < b.length; i++) {
                long v = Ops.index(items.get(i));
                if (v < 0 || v > 255) {
                    throw Errors.value("bytes deve estar em range(0, 256)");
                }
                b[i] = (byte) v;
            }
            return new PyBytes(b);
        });
        ctor(Types.DECIMAL, (f, a, kw) -> {
            Args.n("Decimal", a, 0, 1);
            return a.length == 0 ? BigDecimal.ZERO : toDecimal(a[0]);
        });
        ctor(Types.RANGE, (f, a, kw) -> ((Builtin) Builtins.lookup("range")).call(f, a, kw));

        for (PyType t : new PyType[] {Types.INT, Types.BOOL, Types.FLOAT, Types.STR, Types.LIST, Types.TUPLE,
            Types.SET, Types.FROZENSET, Types.DICT, Types.BYTES}) {
            table.put(t.name, t);
        }
        table.put("decimal", new Builtin("decimal", (f, a, kw) -> {
            Args.n("decimal", a, 1, 1);
            return toDecimal(a[0]);
        }));
        for (PyType t : new PyType[] {Types.BASE_EXCEPTION, Types.EXCEPTION, Types.ARITHMETIC, Types.ZERO_DIV,
            Types.OVERFLOW, Types.LOOKUP, Types.KEY_ERROR, Types.INDEX_ERROR, Types.VALUE_ERROR, Types.TYPE_ERROR,
            Types.RUNTIME_ERROR, Types.NOT_IMPL, Types.ATTRIBUTE_ERROR, Types.NAME_ERROR, Types.UNBOUND,
            Types.ASSERTION, Types.STOP_ITER, Types.IMPORT_ERROR, Types.MODULE_NOT_FOUND}) {
            table.put(t.name, t);
        }
        table.put("Error", Types.EXCEPTION);
    }
}
