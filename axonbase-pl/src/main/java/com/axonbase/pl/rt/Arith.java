package com.axonbase.pl.rt;

import static com.axonbase.pl.rt.Ops.*;

import com.axonbase.value.AxonValue;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

/** Aritmetica dinamica do AxonPL: inteiros de 64 bits com overflow explicito, float, Decimal e sequencias. */
final class Arith {
    private Arith() {
    }

    // ------------------------------------------------------------------ aritmetica

    static Object longOp(int op, long x, long y) {
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
    static Object inplace(Frame f, int op, Object a, Object b) {
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

    static Object negate(Object a) {
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

    static Object positive(Object a) {
        if (a instanceof Long || a instanceof Double || a instanceof BigDecimal) {
            return a;
        }
        if (a instanceof Boolean b) {
            return b ? 1L : 0L;
        }
        throw Errors.type("operando invalido para + unario: '" + Types.nomeDe(a) + "'");
    }

    static Object invert(Object a) {
        if (isIntLike(a)) {
            return ~asLong(a);
        }
        throw Errors.type("operando invalido para ~: '" + Types.nomeDe(a) + "'");
    }

}
