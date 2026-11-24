package com.axonbase.pl.rt;

import com.axonbase.pl.ast.Call;
import com.axonbase.value.AxonValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Funcoes e tipos embutidos (visiveis sem import). */
public final class Builtins {
    private Builtins() {
    }

    private static final Map<String, Object> TABLE = new HashMap<>();

    /** Busca um nome embutido; null se nao existir. */
    public static Object lookup(String name) {
        return TABLE.get(name);
    }

    private static void def(String name, int min, int max, Fn fn) {
        TABLE.put(name, new Builtin(name, (f, a, kw) -> {
            Args.n(name, a, min, max);
            if (kw != null && !kw.isEmpty()) {
                throw Errors.type(name + "() nao aceita argumentos nomeados");
            }
            return fn.call(f, a, kw);
        }));
    }

    private static void defKw(String name, Fn fn) {
        TABLE.put(name, new Builtin(name, fn));
    }

    private static Object inv(Frame f, Object fn, Object x) {
        return Call.invoke(f, fn, new Object[] {x}, null);
    }

    static Object roundHalfEven(Object x, Object nd) {
        if (nd == null || nd instanceof NoneType) {
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
                double r = Math.rint(d);
                if (Math.abs(r) >= 9.2e18) {
                    throw Errors.overflow64();
                }
                return (long) r;
            }
            if (x instanceof BigDecimal b) {
                BigInteger bi = b.setScale(0, RoundingMode.HALF_EVEN).toBigInteger();
                if (bi.bitLength() > 63) {
                    throw Errors.overflow64();
                }
                return bi.longValue();
            }
            throw Errors.type("o tipo '" + Types.nomeDe(x) + "' nao suporta round()");
        }
        long n = Ops.index(nd);
        if (x instanceof Long || x instanceof Boolean) {
            long v = Ops.asLong(x);
            if (n >= 0) {
                return v;
            }
            if (n < -18) {
                return 0L;
            }
            BigDecimal r = BigDecimal.valueOf(v).setScale((int) n, RoundingMode.HALF_EVEN);
            BigInteger bi = r.toBigInteger();
            if (bi.bitLength() > 63) {
                throw Errors.overflow64();
            }
            return bi.longValue();
        }
        if (x instanceof Double d) {
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return d;
            }
            if (n > 400) {
                return d;
            }
            if (n < -400) {
                return 0.0 * Math.signum(d);
            }
            double r = new BigDecimal(d).setScale((int) n, RoundingMode.HALF_EVEN).doubleValue();
            return r == 0 && d < 0 ? -0.0 : r;
        }
        if (x instanceof BigDecimal b) {
            return b.setScale((int) n, RoundingMode.HALF_EVEN);
        }
        throw Errors.type("o tipo '" + Types.nomeDe(x) + "' nao suporta round()");
    }

    private static Object sum(Frame f, Object[] a, Map<String, Object> kw) {
        Args.n("sum", a, 1, 2);
        Args.onlyKw("sum", kw, "start");
        Object start = a.length > 1 ? a[1] : kw != null && kw.containsKey("start") ? kw.get("start") : (Object) 0L;
        if (start instanceof String) {
            throw Errors.type("sum() nao pode somar strings: use ''.join(sequencia)");
        }
        Iterator<Object> it = Ops.iter(f, a[0]);
        Object acc = start;
        double fres = 0;
        double comp = 0;
        boolean fmode = false;
        long n = 0;
        while (it.hasNext()) {
            Object x = it.next();
            if ((++n & 1023) == 0) {
                f.ctx.step();
            }
            if (!fmode && x instanceof Double && (acc instanceof Long || acc instanceof Double)) {
                fmode = true;
                fres = Ops.toDouble(acc);
                comp = 0;
            }
            if (fmode) {
                if (x instanceof Double || x instanceof Long) {
                    double xv = Ops.toDouble(x);
                    double t = fres + xv;
                    if (Math.abs(fres) >= Math.abs(xv)) {
                        comp += (fres - t) + xv;
                    } else {
                        comp += (xv - t) + fres;
                    }
                    fres = t;
                    continue;
                }
                if (comp != 0 && !Double.isNaN(comp) && !Double.isInfinite(comp)) {
                    fres += comp;
                }
                acc = fres;
                fmode = false;
            }
            acc = Ops.binary(f, Ops.ADD, acc, x);
        }
        if (fmode) {
            if (comp != 0 && !Double.isNaN(comp) && !Double.isInfinite(comp)) {
                fres += comp;
            }
            return fres;
        }
        return acc;
    }

    private static Object minmax(Frame f, Object[] a, Map<String, Object> kw, boolean max) {
        String nm = max ? "max" : "min";
        Args.onlyKw(nm, kw, "key", "default");
        if (a.length == 0) {
            throw Errors.type(nm + "() espera pelo menos 1 argumento");
        }
        List<Object> items = a.length == 1 ? Ops.toList(f, a[0]) : java.util.Arrays.asList(a);
        Object key = Args.kw(kw, "key");
        boolean hasKey = key != null && !(key instanceof NoneType);
        if (items.isEmpty()) {
            if (kw != null && kw.containsKey("default")) {
                return kw.get("default");
            }
            throw Errors.value(nm + "() recebeu uma sequencia vazia");
        }
        Object best = items.get(0);
        Object bk = hasKey ? inv(f, key, best) : best;
        for (int i = 1; i < items.size(); i++) {
            Object x = items.get(i);
            Object k = hasKey ? inv(f, key, x) : x;
            if (Ops.compare(max ? Ops.GT : Ops.LT, k, bk)) {
                best = x;
                bk = k;
            }
        }
        return best;
    }

    private static String radix(Object x, int radix, String prefix) {
        long v = Ops.index(x);
        String s = Long.toString(Math.abs(v), radix);
        if (v == Long.MIN_VALUE) {
            s = new BigInteger("9223372036854775808").toString(radix);
        }
        return (v < 0 ? "-" : "") + prefix + s;
    }

    private static boolean isinstance(Object x, Object t) {
        if (t instanceof PyType pt) {
            return pt.isInstance(x);
        }
        if (t instanceof PyTuple tt) {
            for (Object o : tt.a) {
                if (isinstance(x, o)) {
                    return true;
                }
            }
            return false;
        }
        throw Errors.type("isinstance() espera um tipo ou tupla de tipos como segundo argumento");
    }

    static {
        def("len", 1, 1, (f, a, k) -> Ops.len(a[0]));
        defKw("print", (f, a, kw) -> {
            Args.onlyKw("print", kw, "sep", "end", "file", "flush");
            String sep = kw != null && kw.get("sep") instanceof String s ? s : " ";
            String end = kw != null && kw.get("end") instanceof String s ? s : "\n";
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < a.length; i++) {
                if (i > 0) {
                    sb.append(sep);
                }
                sb.append(Repr.str(a[i]));
            }
            if (!end.equals("\n")) {
                sb.append(end);
            }
            if (f.ctx.host != null) {
                f.ctx.host.log("INFO", sb.toString(), List.of());
            }
            return NoneType.NONE;
        });
        defKw("range", (f, a, kw) -> {
            Args.n("range", a, 1, 3);
            Args.noKw("range", kw);
            long start = 0;
            long stop;
            long step = 1;
            if (a.length == 1) {
                stop = Ops.index(a[0]);
            } else {
                start = Ops.index(a[0]);
                stop = Ops.index(a[1]);
                if (a.length == 3) {
                    step = Ops.index(a[2]);
                }
            }
            if (step == 0) {
                throw Errors.value("o terceiro argumento de range() nao pode ser zero");
            }
            return new PyRange(start, stop, step);
        });
        defKw("sorted", (f, a, kw) -> {
            Args.n("sorted", a, 1, 1);
            Args.onlyKw("sorted", kw, "key", "reverse");
            List<Object> items = Ops.toList(f, a[0]);
            Object rev = Args.kw(kw, "reverse");
            Sorting.sort(f, items, Args.kw(kw, "key"), rev != null && Ops.truthy(rev));
            return new PyList(items);
        });
        def("reversed", 1, 1, (f, a, k) -> Seqs.reversed(f, a[0]));
        defKw("enumerate", (f, a, kw) -> {
            Args.n("enumerate", a, 1, 2);
            Args.onlyKw("enumerate", kw, "start");
            Object st = a.length > 1 ? a[1] : Args.kw(kw, "start");
            return Seqs.enumerate(f, a[0], st == null ? 0 : Ops.index(st));
        });
        defKw("zip", (f, a, kw) -> {
            Args.onlyKw("zip", kw, "strict");
            return Seqs.zip(f, a);
        });
        defKw("map", (f, a, kw) -> {
            Args.noKw("map", kw);
            if (a.length < 2) {
                throw Errors.type("map() espera pelo menos 2 argumentos");
            }
            return Seqs.map(f, a[0], java.util.Arrays.copyOfRange(a, 1, a.length));
        });
        def("filter", 2, 2, (f, a, k) -> Seqs.filter(f, a[0], a[1]));
        defKw("sum", Builtins::sum);
        defKw("min", (f, a, kw) -> minmax(f, a, kw, false));
        defKw("max", (f, a, kw) -> minmax(f, a, kw, true));
        def("abs", 1, 1, (f, a, k) -> {
            Object x = a[0];
            if (x instanceof Long l) {
                if (l == Long.MIN_VALUE) {
                    throw Errors.overflow64();
                }
                return Math.abs(l);
            }
            if (x instanceof Double d) {
                return Math.abs(d);
            }
            if (x instanceof Boolean b) {
                return b ? 1L : 0L;
            }
            if (x instanceof BigDecimal d) {
                return d.abs();
            }
            if (x instanceof AxonValue.Duration d) {
                return new AxonValue.Duration(Math.abs(d.millis()));
            }
            throw Errors.type("operando invalido para abs(): '" + Types.nomeDe(x) + "'");
        });
        def("round", 1, 2, (f, a, k) -> roundHalfEven(a[0], a.length > 1 ? a[1] : null));
        defKw("round", (f, a, kw) -> {
            Args.n("round", a, 1, 2);
            Args.onlyKw("round", kw, "ndigits");
            return roundHalfEven(a[0], a.length > 1 ? a[1] : Args.kw(kw, "ndigits"));
        });
        def("divmod", 2, 2, (f, a, k) -> new PyTuple(new Object[] {
            Ops.binary(f, Ops.FLOORDIV, a[0], a[1]), Ops.binary(f, Ops.MOD, a[0], a[1])}));
        def("pow", 2, 3, (f, a, k) -> {
            if (a.length == 2) {
                return Ops.binary(f, Ops.POW, a[0], a[1]);
            }
            long b = Ops.asLong(a[0]);
            long e = Ops.asLong(a[1]);
            long m = Ops.asLong(a[2]);
            if (m == 0) {
                throw Errors.value("pow() 3o argumento nao pode ser zero");
            }
            if (e < 0) {
                throw Errors.value("pow() com modulo nao aceita expoente negativo no AxonPL");
            }
            BigInteger mm = BigInteger.valueOf(Math.abs(m));
            BigInteger r = BigInteger.valueOf(b).modPow(BigInteger.valueOf(e), mm);
            if (m < 0 && r.signum() != 0) {
                r = r.add(BigInteger.valueOf(m));
            }
            return r.longValue();
        });
        def("any", 1, 1, (f, a, k) -> {
            Iterator<Object> it = Ops.iter(f, a[0]);
            while (it.hasNext()) {
                if (Ops.truthy(it.next())) {
                    return true;
                }
            }
            return false;
        });
        def("all", 1, 1, (f, a, k) -> {
            Iterator<Object> it = Ops.iter(f, a[0]);
            while (it.hasNext()) {
                if (!Ops.truthy(it.next())) {
                    return false;
                }
            }
            return true;
        });
        def("isinstance", 2, 2, (f, a, k) -> isinstance(a[0], a[1]));
        def("type", 1, 1, (f, a, k) -> Types.tipoDe(a[0]));
        def("callable", 1, 1, (f, a, k) -> a[0] instanceof PyCallable);
        def("iter", 1, 1, (f, a, k) -> new PyIterator(Ops.iter(f, a[0])));
        def("next", 1, 2, (f, a, k) -> {
            if (!(a[0] instanceof PyIterator it)) {
                throw Errors.type("o objeto do tipo '" + Types.nomeDe(a[0]) + "' nao e um iterador");
            }
            if (it.hasNext()) {
                return it.next();
            }
            if (a.length > 1) {
                return a[1];
            }
            throw new com.axonbase.pl.PlRuntimeError(new PyException(Types.STOP_ITER, new Object[0]));
        });
        def("hash", 1, 1, (f, a, k) -> Ops.hash(a[0]));
        def("repr", 1, 1, (f, a, k) -> Repr.repr(a[0]));
        def("format", 1, 2, (f, a, k) -> Fmt.format(a[0], a.length > 1 ? Args.str(a[1], "format") : ""));
        def("chr", 1, 1, (f, a, k) -> {
            long c = Ops.index(a[0]);
            if (c < 0 || c > 0x10FFFF) {
                throw Errors.value("chr() fora do intervalo");
            }
            return new String(Character.toChars((int) c));
        });
        def("ord", 1, 1, (f, a, k) -> {
            if (!(a[0] instanceof String s) || s.codePointCount(0, s.length()) != 1) {
                throw Errors.type("ord() espera um unico caractere");
            }
            return (long) s.codePointAt(0);
        });
        def("hex", 1, 1, (f, a, k) -> radix(a[0], 16, "0x"));
        def("bin", 1, 1, (f, a, k) -> radix(a[0], 2, "0b"));
        def("oct", 1, 1, (f, a, k) -> radix(a[0], 8, "0o"));
        def("rid", 1, 2, (f, a, k) -> {
            if (a.length == 1) {
                String s = Args.str(a[0], "rid");
                int i = s.indexOf(':');
                if (i <= 0 || i == s.length() - 1) {
                    throw Errors.value("rid() espera 'tabela:chave'");
                }
                String key = s.substring(i + 1);
                Object kv = key.matches("-?\\d{1,18}") ? AxonValue.num(Long.parseLong(key)) : AxonValue.str(key);
                return AxonValue.record(s.substring(0, i), kv).asRecordId();
            }
            String t = Args.str(a[0], "rid");
            AxonValue kv;
            if (a[1] instanceof Long l) {
                kv = AxonValue.num(l.longValue());
            } else if (a[1] instanceof String s) {
                kv = AxonValue.str(s);
            } else {
                throw Errors.type("a chave de rid() deve ser texto ou inteiro");
            }
            return AxonValue.record(t, kv).asRecordId();
        });
        TypeCtors.install(TABLE);
    }

    static List<Object> unusedList() {
        return new ArrayList<>();
    }
}
