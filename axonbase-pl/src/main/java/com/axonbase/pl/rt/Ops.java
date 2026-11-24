package com.axonbase.pl.rt;

import com.axonbase.value.AxonValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

/** Operacoes dinamicas do AxonPL: aritmetica, comparacao, indexacao e iteracao. */
public final class Ops {
    private Ops() {
    }

    public static final int ADD = 0, SUB = 1, MUL = 2, TRUEDIV = 3, FLOORDIV = 4, MOD = 5, POW = 6,
            LSHIFT = 7, RSHIFT = 8, BITAND = 9, BITOR = 10, BITXOR = 11;
    public static final int LT = 0, LE = 1, GT = 2, GE = 3, EQ = 4, NE = 5;
    static final String[] OPSYM = {"+", "-", "*", "/", "//", "%", "**", "<<", ">>", "&", "|", "^"};
    private static final String[] CMPSYM = {"<", "<=", ">", ">=", "==", "!="};
    public static final MathContext MC = new MathContext(28, RoundingMode.HALF_EVEN);
    static final long TWO53 = 1L << 53;

    // ------------------------------------------------------------------ basicos

    public static boolean truthy(Object o) {
        if (o instanceof Boolean b) {
            return b;
        }
        if (o instanceof Long l) {
            return l != 0;
        }
        if (o instanceof String s) {
            return !s.isEmpty();
        }
        if (o instanceof NoneType) {
            return false;
        }
        if (o instanceof Double d) {
            return d != 0;
        }
        if (o instanceof PyList l) {
            return !l.a.isEmpty();
        }
        if (o instanceof PyDict d) {
            return !d.m.isEmpty();
        }
        if (o instanceof PyTuple t) {
            return t.a.length > 0;
        }
        if (o instanceof PySet s) {
            return !s.s.isEmpty();
        }
        if (o instanceof BigDecimal d) {
            return d.signum() != 0;
        }
        if (o instanceof PyRange r) {
            return r.size() > 0;
        }
        if (o instanceof PyBytes b) {
            return b.b.length > 0;
        }
        if (o instanceof AxonValue.Duration d) {
            return d.millis() != 0;
        }
        return true;
    }

    public static void checkHashable(Object k) {
        if (k instanceof PyList || k instanceof PyDict || (k instanceof PySet s && !s.frozen)) {
            throw Errors.type("tipo nao hashable: '" + Types.nomeDe(k) + "'");
        }
        if (k instanceof PyTuple t) {
            for (Object x : t.a) {
                checkHashable(x);
            }
        }
    }

    public static long asLong(Object o) {
        if (o instanceof Long l) {
            return l;
        }
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        throw Errors.type("era esperado um inteiro, mas recebeu '" + Types.nomeDe(o) + "'");
    }

    /** Converte para indice inteiro (list[x], range, ...). */
    public static long index(Object o) {
        if (o instanceof Long l) {
            return l;
        }
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        throw Errors.type("os indices devem ser inteiros, nao '" + Types.nomeDe(o) + "'");
    }

    public static double toDouble(Object o) {
        if (o instanceof Double d) {
            return d;
        }
        if (o instanceof Long l) {
            return l;
        }
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (o instanceof BigDecimal d) {
            return d.doubleValue();
        }
        throw Errors.type("era esperado um numero, mas recebeu '" + Types.nomeDe(o) + "'");
    }

    public static boolean isNum(Object o) {
        return o instanceof Long || o instanceof Double || o instanceof Boolean || o instanceof BigDecimal;
    }

    static boolean isIntLike(Object o) {
        return o instanceof Long || o instanceof Boolean;
    }

    // ------------------------------------------------------------------ aritmetica (ver Arith)

    public static Object binary(Frame f, int op, Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) {
            return Arith.longOp(op, x, y);
        }
        return Arith.binarySlow(f, op, a, b);
    }

    public static Object longOp(int op, long x, long y) {
        return Arith.longOp(op, x, y);
    }

    static Object doubleOp(int op, double x, double y) {
        return Arith.doubleOp(op, x, y);
    }

    /** Operador aumentado (+=): listas e sets mudam no lugar. */
    public static Object inplace(Frame f, int op, Object a, Object b) {
        return Arith.inplace(f, op, a, b);
    }

    public static Object negate(Object a) {
        return Arith.negate(a);
    }

    public static Object positive(Object a) {
        return Arith.positive(a);
    }

    public static Object invert(Object a) {
        return Arith.invert(a);
    }

    // ------------------------------------------------------------------ comparacao

    public static boolean compare(int op, Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) {
            long p = x;
            long q = y;
            return switch (op) {
                case LT -> p < q;
                case LE -> p <= q;
                case GT -> p > q;
                case GE -> p >= q;
                case EQ -> p == q;
                default -> p != q;
            };
        }
        if (op == EQ) {
            return eq(a, b);
        }
        if (op == NE) {
            return !eq(a, b);
        }
        if (a instanceof PySet x && b instanceof PySet y) {
            return switch (op) {
                case LE -> y.s.containsAll(x.s);
                case LT -> y.s.containsAll(x.s) && x.s.size() < y.s.size();
                case GE -> x.s.containsAll(y.s);
                default -> x.s.containsAll(y.s) && x.s.size() > y.s.size();
            };
        }
        int c = cmp3(a, b, op);
        if (c == 2) {
            return false;
        }
        return switch (op) {
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            default -> c >= 0;
        };
    }

    /** Ordena a e b: -1, 0, 1, ou 2 se nao ordenavel (NaN). */
    static int cmp3(Object a, Object b, int op) {
        if (isNum(a) && isNum(b)) {
            return numCmp(a, b);
        }
        if (a instanceof String x && b instanceof String y) {
            int c = x.compareTo(y);
            return c < 0 ? -1 : c > 0 ? 1 : 0;
        }
        if (a instanceof PyList x && b instanceof PyList y) {
            return seqCmp(x.a.toArray(), y.a.toArray(), op);
        }
        if (a instanceof PyTuple x && b instanceof PyTuple y) {
            return seqCmp(x.a, y.a, op);
        }
        if (a instanceof Instant x && b instanceof Instant y) {
            return Integer.signum(x.compareTo(y));
        }
        if (a instanceof AxonValue.Duration x && b instanceof AxonValue.Duration y) {
            return Integer.signum(x.compareTo(y));
        }
        if (a instanceof PyBytes x && b instanceof PyBytes y) {
            return Integer.signum(java.util.Arrays.compare(x.b, y.b));
        }
        throw Errors.type("'" + CMPSYM[op] + "' nao e suportado entre instancias de '" + Types.nomeDe(a)
                + "' e '" + Types.nomeDe(b) + "'");
    }

    private static int seqCmp(Object[] x, Object[] y, int op) {
        int n = Math.min(x.length, y.length);
        for (int i = 0; i < n; i++) {
            if (!eq(x[i], y[i])) {
                return cmp3(x[i], y[i], op);
            }
        }
        return Integer.compare(x.length, y.length);
    }

    static int numCmp(Object a, Object b) {
        if (a instanceof Double || b instanceof Double) {
            if (a instanceof BigDecimal || b instanceof BigDecimal) {
                double dv = a instanceof Double ? (Double) a : (Double) b;
                if (Double.isNaN(dv)) {
                    return 2;
                }
                if (Double.isInfinite(dv)) {
                    boolean aIsD = a instanceof Double;
                    return aIsD ? (dv > 0 ? 1 : -1) : (dv > 0 ? -1 : 1);
                }
                BigDecimal x = a instanceof Double ? new BigDecimal((Double) a) : (BigDecimal) a;
                BigDecimal y = b instanceof Double ? new BigDecimal((Double) b) : (BigDecimal) b;
                return Integer.signum(x.compareTo(y));
            }
            if ((a instanceof Double && Double.isNaN((Double) a)) || (b instanceof Double && Double.isNaN((Double) b))) {
                return 2;
            }
            if (a instanceof Double x && b instanceof Double y) {
                return x < y ? -1 : x > y ? 1 : 0;
            }
            long l = a instanceof Double ? asLong(b) : asLong(a);
            double d = a instanceof Double ? (Double) a : (Double) b;
            int c;
            if (Double.isInfinite(d)) {
                c = d > 0 ? -1 : 1;
            } else if (l > -TWO53 && l < TWO53) {
                c = Double.compare((double) l, d);
                if (l == 0 && d == 0) {
                    c = 0;
                }
            } else {
                c = new BigDecimal(l).compareTo(new BigDecimal(d));
            }
            c = Integer.signum(c);
            return a instanceof Double ? -c : c;
        }
        if (a instanceof BigDecimal || b instanceof BigDecimal) {
            return Integer.signum(Arith.toDec(a).compareTo(Arith.toDec(b)));
        }
        return Long.compare(asLong(a), asLong(b));
    }

    public static boolean eq(Object a, Object b) {
        if (a instanceof String x) {
            return b instanceof String y && x.equals(y);
        }
        if (a instanceof Long x && b instanceof Long y) {
            return x.longValue() == y.longValue();
        }
        if (isNum(a) && isNum(b)) {
            return numCmp(a, b) == 0;
        }
        if (a instanceof NoneType) {
            return b instanceof NoneType;
        }
        if (a instanceof PyList x) {
            if (!(b instanceof PyList y) || x.a.size() != y.a.size()) {
                return false;
            }
            if (x == y) {
                return true;
            }
            for (int i = 0; i < x.a.size(); i++) {
                if (!eq(x.a.get(i), y.a.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof PyTuple x) {
            if (!(b instanceof PyTuple y) || x.a.length != y.a.length) {
                return false;
            }
            for (int i = 0; i < x.a.length; i++) {
                if (!eq(x.a[i], y.a[i])) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof PyDict x) {
            if (!(b instanceof PyDict y) || x.m.size() != y.m.size()) {
                return false;
            }
            for (Map.Entry<Object, Object> e : x.m.entrySet()) {
                Object o = y.m.get(e.getKey());
                if (o == null || !eq(e.getValue(), o)) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof PySet x) {
            return b instanceof PySet y && x.s.equals(y.s);
        }
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return false;
        }
        if (a instanceof PyType || a instanceof PyCallable || a instanceof PyException) {
            return false;
        }
        return a.equals(b);
    }

    /** Operador is (identidade, com valor para ints, bools e None). */
    public static boolean is(Object a, Object b) {
        if (a instanceof NoneType) {
            return b instanceof NoneType;
        }
        if (a instanceof Long x && b instanceof Long y) {
            return x.longValue() == y.longValue();
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return x.booleanValue() == y.booleanValue();
        }
        return a == b;
    }

    public static boolean contains(Frame f, Object container, Object item) {
        if (container instanceof PyList l) {
            for (Object o : l.a) {
                if (o == item || eq(o, item)) {
                    return true;
                }
            }
            return false;
        }
        if (container instanceof PyDict d) {
            Ops.checkHashable(item);
            return d.m.containsKey(item);
        }
        if (container instanceof String s) {
            if (!(item instanceof String t)) {
                throw Errors.type("'in <string>' exige uma string como operando esquerdo, nao '" + Types.nomeDe(item) + "'");
            }
            return s.contains(t);
        }
        if (container instanceof PyTuple t) {
            for (Object o : t.a) {
                if (o == item || eq(o, item)) {
                    return true;
                }
            }
            return false;
        }
        if (container instanceof PySet s) {
            Ops.checkHashable(item);
            return s.s.contains(item);
        }
        if (container instanceof PyRange r) {
            if (!(item instanceof Long) && !(item instanceof Boolean)) {
                return false;
            }
            long v = asLong(item);
            long n = r.size();
            if (n == 0) {
                return false;
            }
            long off = v - r.start;
            return off % r.step == 0 && off / r.step >= 0 && off / r.step < n;
        }
        if (container instanceof PyBytes b) {
            if (item instanceof PyBytes sub) {
                outer:
                for (int i = 0; i + sub.b.length <= b.b.length; i++) {
                    for (int j = 0; j < sub.b.length; j++) {
                        if (b.b[i + j] != sub.b[j]) {
                            continue outer;
                        }
                    }
                    return true;
                }
                return false;
            }
            if (item instanceof Long l) {
                for (byte x : b.b) {
                    if ((x & 0xff) == l) {
                        return true;
                    }
                }
                return false;
            }
        }
        for (Iterator<Object> it = iter(f, container); it.hasNext();) {
            if (eq(it.next(), item)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ indexacao

    private static long norm(long i, int len, String what) {
        long j = i < 0 ? i + len : i;
        if (j < 0 || j >= len) {
            throw Errors.index("indice de " + what + " fora do intervalo");
        }
        return j;
    }

    /** Resolve a fatia: {inicio, fim, passo, quantidade}. */
    public static long[] sliceIdx(PySlice s, int len) {
        long step = s.step instanceof NoneType ? 1 : index(s.step);
        if (step == 0) {
            throw Errors.value("o passo da fatia nao pode ser zero");
        }
        long lo;
        long hi;
        if (s.lo instanceof NoneType) {
            lo = step > 0 ? 0 : len - 1;
        } else {
            lo = index(s.lo);
            if (lo < 0) {
                lo += len;
                if (lo < 0) {
                    lo = step < 0 ? -1 : 0;
                }
            } else if (lo >= len) {
                lo = step < 0 ? len - 1 : len;
            }
        }
        if (s.hi instanceof NoneType) {
            hi = step > 0 ? len : -1;
        } else {
            hi = index(s.hi);
            if (hi < 0) {
                hi += len;
                if (hi < 0) {
                    hi = step < 0 ? -1 : 0;
                }
            } else if (hi >= len) {
                hi = step < 0 ? len - 1 : len;
            }
        }
        long count = step > 0 ? (hi > lo ? (hi - lo - 1) / step + 1 : 0) : (lo > hi ? (lo - hi - 1) / (-step) + 1 : 0);
        return new long[] {lo, hi, step, count};
    }

    public static Object getitem(Frame f, Object o, Object k) {
        if (o instanceof PyList l) {
            if (k instanceof Long i) {
                long j = i < 0 ? i + l.a.size() : i;
                if (j < 0 || j >= l.a.size()) {
                    throw Errors.index("indice de lista fora do intervalo");
                }
                return l.a.get((int) j);
            }
            if (k instanceof PySlice s) {
                long[] r = sliceIdx(s, l.a.size());
                PyList res = new PyList((int) r[3]);
                for (long i = 0, p = r[0]; i < r[3]; i++, p += r[2]) {
                    res.a.add(l.a.get((int) p));
                }
                return res;
            }
            return l.a.get((int) norm(index(k), l.a.size(), "lista"));
        }
        if (o instanceof PyDict d) {
            checkHashable(k);
            Object v = d.m.get(k);
            if (v == null) {
                throw Errors.key(k);
            }
            return v;
        }
        if (o instanceof String s) {
            if (k instanceof PySlice sl) {
                long[] r = sliceIdx(sl, s.length());
                if (r[2] == 1) {
                    return r[3] == 0 ? "" : s.substring((int) r[0], (int) r[1]);
                }
                StringBuilder sb = new StringBuilder();
                for (long i = 0, p = r[0]; i < r[3]; i++, p += r[2]) {
                    sb.append(s.charAt((int) p));
                }
                return sb.toString();
            }
            return String.valueOf(s.charAt((int) norm(index(k), s.length(), "string")));
        }
        if (o instanceof PyTuple t) {
            if (k instanceof PySlice sl) {
                long[] r = sliceIdx(sl, t.a.length);
                Object[] res = new Object[(int) r[3]];
                for (int i = 0; i < r[3]; i++) {
                    res[i] = t.a[(int) (r[0] + i * r[2])];
                }
                return new PyTuple(res);
            }
            return t.a[(int) norm(index(k), t.a.length, "tupla")];
        }
        if (o instanceof PyRange r) {
            if (k instanceof PySlice) {
                throw Errors.type("fatias de range nao sao suportadas no AxonPL");
            }
            return r.at(norm(index(k), (int) Math.min(r.size(), Integer.MAX_VALUE), "range"));
        }
        if (o instanceof PyBytes b) {
            if (k instanceof PySlice sl) {
                long[] r = sliceIdx(sl, b.b.length);
                byte[] res = new byte[(int) r[3]];
                for (int i = 0; i < r[3]; i++) {
                    res[i] = b.b[(int) (r[0] + i * r[2])];
                }
                return new PyBytes(res);
            }
            return (long) (b.b[(int) norm(index(k), b.b.length, "bytes")] & 0xff);
        }
        throw Errors.type("o objeto do tipo '" + Types.nomeDe(o) + "' nao permite acesso por indice");
    }

    public static void setitem(Frame f, Object o, Object k, Object v) {
        if (o instanceof PyList l) {
            if (k instanceof PySlice s) {
                List<Object> items = toList(f, v);
                long[] r = sliceIdx(s, l.a.size());
                if (r[2] == 1) {
                    long lo = r[0];
                    long hi = Math.max(r[1], lo);
                    f.ctx.checkElems((long) l.a.size() - (hi - lo) + items.size());
                    l.a.subList((int) lo, (int) hi).clear();
                    l.a.addAll((int) lo, items);
                } else {
                    if (items.size() != r[3]) {
                        throw Errors.value("tentativa de atribuir sequencia de tamanho " + items.size()
                                + " a fatia estendida de tamanho " + r[3]);
                    }
                    for (int i = 0; i < r[3]; i++) {
                        l.a.set((int) (r[0] + i * r[2]), items.get(i));
                    }
                }
                return;
            }
            l.a.set((int) norm(index(k), l.a.size(), "lista"), v);
            return;
        }
        if (o instanceof PyDict d) {
            checkHashable(k);
            if (!d.m.containsKey(k)) {
                f.ctx.checkElems(d.m.size() + 1L);
            }
            d.m.put(k, v);
            return;
        }
        throw Errors.type("o objeto do tipo '" + Types.nomeDe(o) + "' nao permite atribuicao por indice");
    }

    public static void delitem(Frame f, Object o, Object k) {
        if (o instanceof PyList l) {
            if (k instanceof PySlice s) {
                long[] r = sliceIdx(s, l.a.size());
                if (r[2] == 1) {
                    if (r[1] > r[0]) {
                        l.a.subList((int) r[0], (int) r[1]).clear();
                    }
                } else {
                    List<Integer> idx = new ArrayList<>();
                    for (int i = 0; i < r[3]; i++) {
                        idx.add((int) (r[0] + i * r[2]));
                    }
                    idx.sort(java.util.Collections.reverseOrder());
                    for (int i : idx) {
                        l.a.remove(i);
                    }
                }
                return;
            }
            l.a.remove((int) norm(index(k), l.a.size(), "lista"));
            return;
        }
        if (o instanceof PyDict d) {
            checkHashable(k);
            if (d.m.remove(k) == null) {
                throw Errors.key(k);
            }
            return;
        }
        throw Errors.type("o objeto do tipo '" + Types.nomeDe(o) + "' nao permite remover itens");
    }

    public static long len(Object o) {
        if (o instanceof String s) {
            return s.length();
        }
        if (o instanceof PyList l) {
            return l.a.size();
        }
        if (o instanceof PyDict d) {
            return d.m.size();
        }
        if (o instanceof PyTuple t) {
            return t.a.length;
        }
        if (o instanceof PySet s) {
            return s.s.size();
        }
        if (o instanceof PyRange r) {
            return r.size();
        }
        if (o instanceof PyBytes b) {
            return b.b.length;
        }
        throw Errors.type("o objeto do tipo '" + Types.nomeDe(o) + "' nao tem len()");
    }

    // ------------------------------------------------------------------ iteracao

    public static Iterator<Object> iter(Frame f, Object o) {
        if (o instanceof PyList l) {
            return new Iterator<>() {
                int i;

                @Override
                public boolean hasNext() {
                    return i < l.a.size();
                }

                @Override
                public Object next() {
                    return l.a.get(i++);
                }
            };
        }
        if (o instanceof PyTuple t) {
            return java.util.Arrays.asList(t.a).iterator();
        }
        if (o instanceof PyRange r) {
            return new Iterator<>() {
                long i;
                final long n = r.size();

                @Override
                public boolean hasNext() {
                    return i < n;
                }

                @Override
                public Object next() {
                    return r.at(i++);
                }
            };
        }
        if (o instanceof String s) {
            return new Iterator<>() {
                int i;

                @Override
                public boolean hasNext() {
                    return i < s.length();
                }

                @Override
                public Object next() {
                    return String.valueOf(s.charAt(i++));
                }
            };
        }
        if (o instanceof PyDict d) {
            return new ArrayList<>(d.m.keySet()).iterator();
        }
        if (o instanceof PySet s) {
            return new ArrayList<>(s.s).iterator();
        }
        if (o instanceof PyIterator it) {
            return it;
        }
        if (o instanceof PyBytes b) {
            return new Iterator<>() {
                int i;

                @Override
                public boolean hasNext() {
                    return i < b.b.length;
                }

                @Override
                public Object next() {
                    if (i >= b.b.length) {
                        throw new NoSuchElementException();
                    }
                    return (long) (b.b[i++] & 0xff);
                }
            };
        }
        throw Errors.type("o objeto do tipo '" + Types.nomeDe(o) + "' nao e iteravel");
    }

    /** Materializa um iteravel como lista Java, respeitando limites. */
    public static List<Object> toList(Frame f, Object o) {
        if (o instanceof PyList l) {
            return new ArrayList<>(l.a);
        }
        if (o instanceof PyTuple t) {
            return new ArrayList<>(java.util.Arrays.asList(t.a));
        }
        Ctx c = f.ctx;
        if (o instanceof PyRange r) {
            c.checkElems(r.size());
            c.alloc(r.size() * 16);
        }
        ArrayList<Object> out = new ArrayList<>();
        Iterator<Object> it = iter(f, o);
        long n = 0;
        while (it.hasNext()) {
            out.add(it.next());
            if ((++n & 1023) == 0) {
                c.step();
                c.checkElems(n);
            }
        }
        c.checkElems(n);
        return out;
    }

    /** Hash aproximado de um valor hashable. */
    public static long hash(Object o) {
        checkHashable(o);
        if (o instanceof Long l) {
            return l;
        }
        if (o instanceof Boolean b) {
            return b ? 1 : 0;
        }
        if (o instanceof Double d && d == Math.floor(d) && Math.abs(d) < 9e18) {
            return (long) (double) d;
        }
        return o.hashCode();
    }

    /** Divisao inteira exata auxiliar para BigInteger. */
    static BigInteger big(long v) {
        return BigInteger.valueOf(v);
    }
}
