package com.axonbase.pl.rt;

import com.axonbase.pl.PlProgram;
import com.axonbase.value.AxonValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Modulos embutidos (math, json, time, decimal) e resolucao de import. */
public final class Modules {
    private Modules() {
    }

    private static final Map<String, PyModule> BUILTIN = new LinkedHashMap<>();
    private static final ThreadLocal<Set<String>> LOADING = ThreadLocal.withInitial(HashSet::new);

    /** Verdadeiro se {@code name} e um modulo embutido (math, json, time, decimal). */
    public static boolean embutido(String name) {
        return BUILTIN.containsKey(name);
    }

    /** Resolve um import. */
    public static Object importModule(Frame f, String name) {
        PyModule b = BUILTIN.get(name);
        if (b != null) {
            return b;
        }
        Object cached = f.ctx.modules.get("mod:" + name);
        if (cached != null) {
            return cached;
        }
        if (f.ctx.host != null) {
            PlProgram p = f.ctx.host.resolverImport(name);
            if (p != null) {
                Set<String> loading = LOADING.get();
                if (!loading.add(name)) {
                    throw Errors.make(Types.IMPORT_ERROR, "import circular detectado envolvendo '" + name + "'");
                }
                try {
                    ModuleInst mi = ModuleLoader.load(f.ctx, p, f);
                    PyModule m = new PyModule(name);
                    ModuleLoader.exportar(mi, m.attrs);
                    f.ctx.modules.put("mod:" + name, m);
                    return m;
                } finally {
                    loading.remove(name);
                }
            }
            if (f.ctx.host.modulosNativos().contains(name)) {
                return new HostModule(name);
            }
        }
        throw Errors.make(Types.MODULE_NOT_FOUND, "o modulo '" + name + "' nao foi encontrado");
    }

    private static void fn(PyModule m, String name, int min, int max, Fn fn) {
        m.def(name, new Builtin(m.nome + "." + name, (f, a, kw) -> {
            Args.n(m.nome + "." + name, a, min, max);
            Args.noKw(m.nome + "." + name, kw);
            return fn.call(f, a, kw);
        }));
    }

    private static double d(Object o) {
        return Ops.toDouble(o);
    }

    private static Object intResult(double v) {
        if (Double.isNaN(v)) {
            throw Errors.value("nao e possivel converter NaN para inteiro");
        }
        if (Double.isInfinite(v)) {
            throw Errors.overflow("nao e possivel converter infinito para inteiro");
        }
        if (Math.abs(v) >= 9.2e18) {
            throw Errors.overflow64();
        }
        return (long) v;
    }

    private static PyModule math() {
        PyModule m = new PyModule("math");
        m.def("pi", Math.PI).def("e", Math.E).def("tau", 2 * Math.PI).def("inf", Double.POSITIVE_INFINITY)
                .def("nan", Double.NaN);
        fn(m, "floor", 1, 1, (f, a, k) -> a[0] instanceof Long || a[0] instanceof Boolean ? (Object) Ops.asLong(a[0])
                : a[0] instanceof BigDecimal b ? Builtins_toLong(b.setScale(0, RoundingMode.FLOOR))
                : intResult(Math.floor(d(a[0]))));
        fn(m, "ceil", 1, 1, (f, a, k) -> a[0] instanceof Long || a[0] instanceof Boolean ? (Object) Ops.asLong(a[0])
                : a[0] instanceof BigDecimal b ? Builtins_toLong(b.setScale(0, RoundingMode.CEILING))
                : intResult(Math.ceil(d(a[0]))));
        fn(m, "trunc", 1, 1, (f, a, k) -> a[0] instanceof Long || a[0] instanceof Boolean ? (Object) Ops.asLong(a[0])
                : a[0] instanceof BigDecimal b ? Builtins_toLong(b.setScale(0, RoundingMode.DOWN))
                : intResult(d(a[0]) < 0 ? Math.ceil(d(a[0])) : Math.floor(d(a[0]))));
        fn(m, "sqrt", 1, 1, (f, a, k) -> {
            double x = d(a[0]);
            if (x < 0) {
                throw Errors.value("dominio matematico invalido para sqrt");
            }
            return Math.sqrt(x);
        });
        fn(m, "pow", 2, 2, (f, a, k) -> Ops.doubleOp(Ops.POW, d(a[0]), d(a[1])));
        fn(m, "exp", 1, 1, (f, a, k) -> {
            double r = Math.exp(d(a[0]));
            if (Double.isInfinite(r) && !Double.isInfinite(d(a[0]))) {
                throw Errors.overflow("resultado numerico fora do intervalo");
            }
            return r;
        });
        fn(m, "log", 1, 2, (f, a, k) -> {
            double x = d(a[0]);
            if (x <= 0) {
                throw Errors.value("dominio matematico invalido para log");
            }
            if (a.length == 2) {
                double b = d(a[1]);
                if (b <= 0 || b == 1) {
                    throw Errors.value("base invalida para log");
                }
                return Math.log(x) / Math.log(b);
            }
            return Math.log(x);
        });
        fn(m, "log10", 1, 1, (f, a, k) -> {
            if (d(a[0]) <= 0) {
                throw Errors.value("dominio matematico invalido para log10");
            }
            return Math.log10(d(a[0]));
        });
        fn(m, "log2", 1, 1, (f, a, k) -> {
            if (d(a[0]) <= 0) {
                throw Errors.value("dominio matematico invalido para log2");
            }
            return Math.log(d(a[0])) / Math.log(2);
        });
        fn(m, "sin", 1, 1, (f, a, k) -> Math.sin(d(a[0])));
        fn(m, "cos", 1, 1, (f, a, k) -> Math.cos(d(a[0])));
        fn(m, "tan", 1, 1, (f, a, k) -> Math.tan(d(a[0])));
        fn(m, "atan", 1, 1, (f, a, k) -> Math.atan(d(a[0])));
        fn(m, "atan2", 2, 2, (f, a, k) -> Math.atan2(d(a[0]), d(a[1])));
        fn(m, "hypot", 2, 2, (f, a, k) -> Math.hypot(d(a[0]), d(a[1])));
        fn(m, "degrees", 1, 1, (f, a, k) -> Math.toDegrees(d(a[0])));
        fn(m, "radians", 1, 1, (f, a, k) -> Math.toRadians(d(a[0])));
        fn(m, "isnan", 1, 1, (f, a, k) -> Double.isNaN(d(a[0])));
        fn(m, "isinf", 1, 1, (f, a, k) -> Double.isInfinite(d(a[0])));
        fn(m, "isfinite", 1, 1, (f, a, k) -> !Double.isNaN(d(a[0])) && !Double.isInfinite(d(a[0])));
        fn(m, "fabs", 1, 1, (f, a, k) -> Math.abs(d(a[0])));
        fn(m, "copysign", 2, 2, (f, a, k) -> Math.copySign(d(a[0]), d(a[1])));
        fn(m, "isclose", 2, 2, (f, a, k) -> {
            double x = d(a[0]);
            double y = d(a[1]);
            return Math.abs(x - y) <= Math.max(1e-9 * Math.max(Math.abs(x), Math.abs(y)), 0.0);
        });
        fn(m, "gcd", 2, 2, (f, a, k) -> {
            long x = Math.abs(Ops.asLong(a[0]));
            long y = Math.abs(Ops.asLong(a[1]));
            while (y != 0) {
                long t = x % y;
                x = y;
                y = t;
            }
            return x;
        });
        fn(m, "factorial", 1, 1, (f, a, k) -> {
            long n = Ops.asLong(a[0]);
            if (n < 0) {
                throw Errors.value("factorial() nao aceita valores negativos");
            }
            long r = 1;
            for (long i = 2; i <= n; i++) {
                r = (Long) Ops.longOp(Ops.MUL, r, i);
            }
            return r;
        });
        fn(m, "fsum", 1, 1, (f, a, k) -> {
            BigDecimal s = BigDecimal.ZERO;
            for (Object o : Ops.toList(f, a[0])) {
                double x = d(o);
                if (Double.isNaN(x) || Double.isInfinite(x)) {
                    return x;
                }
                s = s.add(new BigDecimal(x));
            }
            return s.doubleValue();
        });
        return m;
    }

    @SuppressWarnings("checkstyle:MethodName")
    private static Object Builtins_toLong(BigDecimal b) {
        BigInteger bi = b.toBigInteger();
        if (bi.bitLength() > 63) {
            throw Errors.overflow64();
        }
        return bi.longValue();
    }

    private static Instant parseInstant(String s) {
        String t = s.strip();
        try {
            return Instant.parse(t);
        } catch (RuntimeException e1) {
            try {
                return java.time.OffsetDateTime.parse(t).toInstant();
            } catch (RuntimeException e2) {
                try {
                    return java.time.LocalDateTime.parse(t.replace(' ', 'T')).toInstant(ZoneOffset.UTC);
                } catch (RuntimeException e3) {
                    try {
                        return LocalDate.parse(t).atStartOfDay().toInstant(ZoneOffset.UTC);
                    } catch (RuntimeException e4) {
                        throw Errors.value("data/hora invalida: " + Repr.repr(s));
                    }
                }
            }
        }
    }

    private static String strftime(Instant t, String fmt) {
        ZonedDateTime z = t.atZone(ZoneOffset.UTC);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < fmt.length(); i++) {
            char c = fmt.charAt(i);
            if (c != '%' || i + 1 >= fmt.length()) {
                sb.append(c);
                continue;
            }
            char e = fmt.charAt(++i);
            switch (e) {
                case 'Y' -> sb.append(String.format("%04d", z.getYear()));
                case 'y' -> sb.append(String.format("%02d", z.getYear() % 100));
                case 'm' -> sb.append(String.format("%02d", z.getMonthValue()));
                case 'd' -> sb.append(String.format("%02d", z.getDayOfMonth()));
                case 'H' -> sb.append(String.format("%02d", z.getHour()));
                case 'M' -> sb.append(String.format("%02d", z.getMinute()));
                case 'S' -> sb.append(String.format("%02d", z.getSecond()));
                case 'f' -> sb.append(String.format("%06d", z.getNano() / 1000));
                case 'j' -> sb.append(String.format("%03d", z.getDayOfYear()));
                case 'Z' -> sb.append("UTC");
                case 'z' -> sb.append("+0000");
                case '%' -> sb.append('%');
                default -> throw Errors.value("diretiva de formato de data desconhecida: %" + e);
            }
        }
        return sb.toString();
    }

    private static PyModule time() {
        PyModule m = new PyModule("time");
        fn(m, "now", 0, 0, (f, a, k) -> Instant.now());
        fn(m, "parse", 1, 1, (f, a, k) -> parseInstant(Args.str(a[0], "time.parse")));
        fn(m, "format", 1, 2, (f, a, k) -> {
            if (!(a[0] instanceof Instant t)) {
                throw Errors.type("time.format() espera um datetime");
            }
            return a.length == 1 ? t.toString() : strftime(t, Args.str(a[1], "time.format"));
        });
        fn(m, "timestamp", 1, 1, (f, a, k) -> {
            if (!(a[0] instanceof Instant t)) {
                throw Errors.type("time.timestamp() espera um datetime");
            }
            return t.toEpochMilli() / 1000.0;
        });
        fn(m, "from_timestamp", 1, 1, (f, a, k) -> Instant.ofEpochMilli(Math.round(d(a[0]) * 1000)));
        fn(m, "milliseconds", 1, 1, (f, a, k) -> new AxonValue.Duration(Ops.asLong(a[0])));
        fn(m, "seconds", 1, 1, (f, a, k) -> new AxonValue.Duration(Math.round(d(a[0]) * 1000)));
        fn(m, "minutes", 1, 1, (f, a, k) -> new AxonValue.Duration(Math.round(d(a[0]) * 60_000)));
        fn(m, "hours", 1, 1, (f, a, k) -> new AxonValue.Duration(Math.round(d(a[0]) * 3_600_000)));
        fn(m, "days", 1, 1, (f, a, k) -> new AxonValue.Duration(Math.round(d(a[0]) * 86_400_000)));
        return m;
    }

    private static PyModule decimal() {
        PyModule m = new PyModule("decimal");
        m.def("Decimal", Types.DECIMAL);
        return m;
    }

    static {
        BUILTIN.put("math", math());
        BUILTIN.put("json", Json.module());
        BUILTIN.put("time", time());
        BUILTIN.put("decimal", decimal());
    }
}
