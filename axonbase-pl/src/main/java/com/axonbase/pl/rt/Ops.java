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
    private static final String[] OPSYM = {"+", "-", "*", "/", "//", "%", "**", "<<", ">>", "&", "|", "^"};
    private static final String[] CMPSYM = {"<", "<=", ">", ">=", "==", "!="};
    public static final MathContext MC = new MathContext(28, RoundingMode.HALF_EVEN);
    private static final long TWO53 = 1L << 53;

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

    private static boolean isIntLike(Object o) {
        return o instanceof Long || o instanceof Boolean;
    }

    // ------------------------------------------------------------------ aritmetica

    public static Object binary(Frame f, int op, Object a, Object b) {
        if (a instanceof Long x && b instanceof Long y) {
            return longOp(op, x, y);
        }
        return binarySlow(f, op, a, b);
    }

    public static Object longOp(int op, long x, long y) {
        switch (op) {
            case ADD: {
                long r = x + y;
                if (((x ^ r) & (y ^ r)) < 0) {
                    throw Errors.overflow64();
                }
                return r;
            }
            case SUB: {
                long r = x - y;
                if (((x ^ y) & (x ^ r)) < 0) {
                    throw Errors.overflow64();
                }
                return r;
            }
            case MUL: {
                long hi = Math.multiplyHigh(x, y);
                long lo = x * y;
                if ((hi == 0 && lo >= 0) || (hi == -1 && lo < 0)) {
                    return lo;
                }
                throw Errors.overflow64();
            }
            case TRUEDIV:
                if (y == 0) {
                    throw Errors.zero("divisao por zero");
                }
                return trueDiv(x, y);
            case FLOORDIV:
                if (y == 0) {
                    throw Errors.zero("divisao inteira ou modulo por zero");
                }
                if (x == Long.MIN_VALUE && y == -1) {
                    throw Errors.overflow64();
                }
                return Math.floorDiv(x, y);
            case MOD:
                if (y == 0) {
                    throw Errors.zero("divisao inteira ou modulo por zero");
                }
                return Math.floorMod(x, y);
            case POW:
                return ipow(x, y);
            case LSHIFT: {
                if (y < 0) {
                    throw Errors.value("contagem de deslocamento negativa");
                }
                if (x == 0) {
                    return 0L;
                }
                if (y >= 63) {
                    throw Errors.overflow64();
                }
                long r = x << y;
                if ((r >> y) != x) {
                    throw Errors.overflow64();
                }
                return r;
            }
            case RSHIFT:
                if (y < 0) {
                    throw Errors.value("contagem de deslocamento negativa");
                }
                return y >= 64 ? (x < 0 ? -1L : 0L) : x >> y;
            case BITAND:
                return x & y;
            case BITOR:
                return x | y;
            default:
                return x ^ y;
        }
    }

    static double trueDiv(long x, long y) {
        if (x > -TWO53 && x < TWO53 && y > -TWO53 && y < TWO53) {
            return (double) x / (double) y;
        }
        return new BigDecimal(x).divide(new BigDecimal(y), MathContext.DECIMAL128).doubleValue();
    }

    static Object ipow(long x, long y) {
        if (y < 0) {
            if (x == 0) {
                throw Errors.zero("0 nao pode ser elevado a uma potencia negativa");
            }
            return Math.pow(x, y);
        }
        if (x == 0 || x == 1 || y == 0) {
            return y == 0 ? 1L : x;
        }
        if (x == -1) {
            return (y & 1) == 0 ? 1L : -1L;
        }
        long r = 1;
        long base = x;
        long e = y;
        try {
            while (e > 0) {
                if ((e & 1) != 0) {
                    r = Math.multiplyExact(r, base);
                }
                e >>= 1;
                if (e > 0) {
                    base = Math.multiplyExact(base, base);
                }
            }
        } catch (ArithmeticException ex) {
            throw Errors.overflow64();
        }
        return r;
    }

    static Object doubleOp(int op, double x, double y) {
        switch (op) {
            case ADD:
                return x + y;
            case SUB:
                return x - y;
            case MUL:
                return x * y;
            case TRUEDIV:
                if (y == 0) {
                    throw Errors.zero("divisao de float por zero");
                }
                return x / y;
            case FLOORDIV:
            case MOD: {
                if (y == 0) {
                    throw Errors.zero("divisao de float por zero ou modulo por zero");
                }
                double mod = x % y;
                double div = (x - mod) / y;
                if (mod != 0) {
                    if ((y < 0) != (mod < 0)) {
                        mod += y;
                        div -= 1.0;
                    }
                } else {
                    mod = Math.copySign(0.0, y);
                }
                if (op == MOD) {
                    return mod;
                }
                double fl;
                if (div != 0) {
                    fl = Math.floor(div);
                    if (div - fl > 0.5) {
                        fl += 1.0;
                    }
                } else {
                    fl = Math.copySign(0.0, x / y);
                }
                return fl;
            }
            case POW: {
                if (x == 0 && y < 0) {
                    throw Errors.zero("0.0 nao pode ser elevado a uma potencia negativa");
                }
                if (x < 0 && y != Math.floor(y) && !Double.isInfinite(y)) {
                    throw Errors.value("resultado complexo nao e suportado no AxonPL");
                }
                double r = Math.pow(x, y);
                if (Double.isInfinite(r) && !Double.isInfinite(x) && !Double.isInfinite(y)) {
                    throw Errors.overflow("resultado numerico fora do intervalo");
                }
                return r;
            }
            default:
                throw Errors.type("tipos de operando nao suportados para " + OPSYM[op] + ": 'float' e 'float'");
        }
    }

    static Object decOp(int op, BigDecimal x, BigDecimal y) {
        switch (op) {
            case ADD:
                return x.add(y, MC);
            case SUB:
                return x.subtract(y, MC);
            case MUL:
                return x.multiply(y, MC);
            case TRUEDIV:
                if (y.signum() == 0) {
                    throw Errors.zero("divisao de Decimal por zero");
                }
                return x.divide(y, MC);
            case FLOORDIV: {
                if (y.signum() == 0) {
                    throw Errors.zero("divisao de Decimal por zero");
                }
                BigDecimal q = x.divideToIntegralValue(y);
                return q.scale() > 0 ? q.setScale(0, RoundingMode.UNNECESSARY) : q;
            }
            case MOD:
                if (y.signum() == 0) {
                    throw Errors.zero("modulo de Decimal por zero");
                }
                return x.remainder(y);
            case POW: {
                if (y.stripTrailingZeros().scale() > 0) {
                    throw Errors.value("potencia de Decimal com expoente fracionario nao e suportada");
                }
                int n = y.intValueExact();
                if (n >= 0) {
                    return x.pow(n, MC);
                }
                return BigDecimal.ONE.divide(x.pow(-n, MC), MC);
            }
            default:
                throw Errors.type("tipos de operando nao suportados para " + OPSYM[op] + ": 'Decimal' e 'Decimal'");
        }
    }

    private static PlTypeErr bad(int op, Object a, Object b) {
        return null;
    }

    private interface PlTypeErr {
    }

    static RuntimeException badOperands(int op, Object a, Object b) {
        return Errors.type("tipos de operando nao suportados para " + OPSYM[op] + ": '" + Types.nomeDe(a)
                + "' e '" + Types.nomeDe(b) + "'");
    }

    static Object binarySlow(Frame f, int op, Object a, Object b) {
        if (a instanceof Boolean x && b instanceof Boolean y && op >= BITAND) {
            return op == BITAND ? (x & y) : op == BITOR ? (x | y) : (x ^ y);
        }
        if (isIntLike(a) && isIntLike(b)) {
            return longOp(op, asLong(a), asLong(b));
        }
        boolean an = isNum(a);
        boolean bn = isNum(b);
        if (an && bn) {
            if (a instanceof BigDecimal || b instanceof BigDecimal) {
                if (a instanceof Double || b instanceof Double) {
                    throw badOperands(op, a, b);
                }
                return decOp(op, toDec(a), toDec(b));
            }
            if (op >= LSHIFT) {
                throw badOperands(op, a, b);
            }
            return doubleOp(op, toDouble(a), toDouble(b));
        }
        Ctx c = f == null ? null : f.ctx;
        switch (op) {
            case ADD:
                if (a instanceof String x && b instanceof String y) {
                    if (c != null) {
                        c.checkStr((long) x.length() + y.length());
                    }
                    return x.concat(y);
                }
                if (a instanceof PyList x && b instanceof PyList y) {
                    if (c != null) {
                        c.checkElems((long) x.a.size() + y.a.size());
                    }
                    PyList r = new PyList(x.a.size() + y.a.size());
                    r.a.addAll(x.a);
                    r.a.addAll(y.a);
                    return r;
                }
                if (a instanceof PyTuple x && b instanceof PyTuple y) {
                    Object[] r = new Object[x.a.length + y.a.length];
                    System.arraycopy(x.a, 0, r, 0, x.a.length);
                    System.arraycopy(y.a, 0, r, x.a.length, y.a.length);
                    return new PyTuple(r);
                }
                if (a instanceof PyBytes x && b instanceof PyBytes y) {
                    byte[] r = new byte[x.b.length + y.b.length];
                    System.arraycopy(x.b, 0, r, 0, x.b.length);
                    System.arraycopy(y.b, 0, r, x.b.length, y.b.length);
                    return new PyBytes(r);
                }
                if (a instanceof Instant t && b instanceof AxonValue.Duration d) {
                    return t.plusMillis(d.millis());
                }
                if (a instanceof AxonValue.Duration d && b instanceof Instant t) {
                    return t.plusMillis(d.millis());
                }
                if (a instanceof AxonValue.Duration x && b instanceof AxonValue.Duration y) {
                    return new AxonValue.Duration(x.millis() + y.millis());
                }
                break;
            case SUB:
                if (a instanceof Instant t && b instanceof AxonValue.Duration d) {
                    return t.minusMillis(d.millis());
                }
                if (a instanceof Instant t && b instanceof Instant u) {
                    return new AxonValue.Duration(t.toEpochMilli() - u.toEpochMilli());
                }
                if (a instanceof AxonValue.Duration x && b instanceof AxonValue.Duration y) {
                    return new AxonValue.Duration(x.millis() - y.millis());
                }
                if (a instanceof PySet x && b instanceof PySet y) {
                    PySet r = new PySet(x.frozen);
                    for (Object o : x.s) {
                        if (!y.s.contains(o)) {
                            r.s.add(o);
                        }
                    }
                    return r;
                }
                break;
            case MUL:
                if (isIntLike(b) && !isIntLike(a)) {
                    Object t = a;
                    a = b;
                    b = t;
                }
                if (isIntLike(a)) {
                    long n = Math.max(asLong(a), 0);
                    if (b instanceof String s) {
                        if (c != null) {
                            c.checkStr(n * s.length());
                        }
                        return s.repeat((int) n);
                    }
                    if (b instanceof PyList l) {
                        if (c != null) {
                            c.checkElems(n * l.a.size());
                            c.alloc(n * l.a.size() * 8);
                        }
                        PyList r = new PyList((int) Math.min(n * l.a.size(), 1 << 20));
                        for (long i = 0; i < n; i++) {
                            r.a.addAll(l.a);
                        }
                        return r;
                    }
                    if (b instanceof PyTuple t) {
                        if (c != null) {
                            c.checkElems(n * t.a.length);
                        }
                        Object[] r = new Object[(int) (n * t.a.length)];
                        for (int i = 0; i < n; i++) {
                            System.arraycopy(t.a, 0, r, i * t.a.length, t.a.length);
                        }
                        return new PyTuple(r);
                    }
                    if (b instanceof AxonValue.Duration d) {
                        return new AxonValue.Duration(d.millis() * n);
                    }
                }
                break;
            case MOD:
                if (a instanceof String s) {
                    return Fmt.percent(f, s, b);
                }
                break;
            case BITOR:
                if (a instanceof PySet x && b instanceof PySet y) {
                    PySet r = new PySet(x.frozen);
                    r.s.addAll(x.s);
                    r.s.addAll(y.s);
                    return r;
                }
                if (a instanceof PyDict x && b instanceof PyDict y) {
                    PyDict r = new PyDict();
                    r.m.putAll(x.m);
                    r.m.putAll(y.m);
                    return r;
                }
                break;
            case BITAND:
                if (a instanceof PySet x && b instanceof PySet y) {
                    PySet r = new PySet(x.frozen);
                    for (Object o : x.s) {
                        if (y.s.contains(o)) {
                            r.s.add(o);
                        }
                    }
                    return r;
                }
                break;
            case BITXOR:
                if (a instanceof PySet x && b instanceof PySet y) {
                    PySet r = new PySet(x.frozen);
                    for (Object o : x.s) {
                        if (!y.s.contains(o)) {
                            r.s.add(o);
                        }
                    }
                    for (Object o : y.s) {
                        if (!x.s.contains(o)) {
                            r.s.add(o);
                        }
                    }
                    return r;
                }
                break;
            default:
                break;
        }
        throw badOperands(op, a, b);
    }

    static BigDecimal toDec(Object o) {
        if (o instanceof BigDecimal d) {
            return d;
        }
        return BigDecimal.valueOf(asLong(o));
    }

    /** Operador aumentado (+=): listas e sets mudam no lugar. */
    public static Object inplace(Frame f, int op, Object a, Object b) {
        if (op == ADD && a instanceof PyList l) {
            List<Object> items = toList(f, b);
            f.ctx.checkElems((long) l.a.size() + items.size());
            l.a.addAll(items);
            return l;
        }
        if (op == MUL && a instanceof PyList l && b instanceof Long n) {
            Object r = binary(f, MUL, a, b);
            l.a.clear();
            l.a.addAll(((PyList) r).a);
            return l;
        }
        if (a instanceof PySet s && !s.frozen && b instanceof PySet o && (op == BITOR || op == BITAND || op == SUB || op == BITXOR)) {
            PySet r = (PySet) binary(f, op, a, b);
            s.s.clear();
            s.s.addAll(r.s);
            return s;
        }
        if (op == BITOR && a instanceof PyDict d && b instanceof PyDict e) {
            d.m.putAll(e.m);
            return d;
        }
        return binary(f, op, a, b);
    }

    public static Object negate(Object a) {
        if (a instanceof Long l) {
            if (l == Long.MIN_VALUE) {
                throw Errors.overflow64();
            }
            return -l;
        }
        if (a instanceof Double d) {
            return -d;
        }
        if (a instanceof Boolean b) {
            return b ? -1L : 0L;
        }
        if (a instanceof BigDecimal d) {
            return d.negate();
        }
        if (a instanceof AxonValue.Duration d) {
            return new AxonValue.Duration(-d.millis());
        }
        throw Errors.type("operando invalido para - unario: '" + Types.nomeDe(a) + "'");
    }

    public static Object positive(Object a) {
        if (a instanceof Long || a instanceof Double || a instanceof BigDecimal) {
            return a;
        }
        if (a instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        throw Errors.type("operando invalido para + unario: '" + Types.nomeDe(a) + "'");
    }

    public static Object invert(Object a) {
        if (isIntLike(a)) {
            return ~asLong(a);
        }
        throw Errors.type("operando invalido para ~: '" + Types.nomeDe(a) + "'");
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
            return Integer.signum(toDec(a).compareTo(toDec(b)));
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
