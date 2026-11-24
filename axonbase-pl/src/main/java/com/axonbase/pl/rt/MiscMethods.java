package com.axonbase.pl.rt;

import com.axonbase.value.AxonValue;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** Metodos de int, float, Decimal, datetime, duration e bytes. */
public final class MiscMethods {
    private MiscMethods() {
    }

    public static final Map<String, Method> INT = new HashMap<>();
    public static final Map<String, Method> FLOAT = new HashMap<>();
    public static final Map<String, Method> DEC = new HashMap<>();
    public static final Map<String, Method> INSTANT = new HashMap<>();
    public static final Map<String, Method> DURATION = new HashMap<>();
    public static final Map<String, Method> BYTES = new HashMap<>();

    static {
        INT.put("bit_length", (f, x, a, kw) -> {
            long v = Ops.asLong(x);
            return (long) (64 - Long.numberOfLeadingZeros(v < 0 ? ~v : v));
        });
        FLOAT.put("is_integer", (f, x, a, kw) -> {
            double d = (Double) x;
            return !Double.isNaN(d) && !Double.isInfinite(d) && d == Math.floor(d);
        });
        DEC.put("quantize", (f, x, a, kw) -> {
            Args.n("Decimal.quantize", a, 1, 1);
            if (!(a[0] instanceof BigDecimal e)) {
                throw Errors.type("quantize() espera um Decimal");
            }
            return ((BigDecimal) x).setScale(e.scale(), RoundingMode.HALF_EVEN);
        });
        DEC.put("is_integer", (f, x, a, kw) -> ((BigDecimal) x).stripTrailingZeros().scale() <= 0);
        INSTANT.put("isoformat", (f, x, a, kw) -> ((Instant) x).toString());
        INSTANT.put("timestamp", (f, x, a, kw) -> ((Instant) x).toEpochMilli() / 1000.0);
        DURATION.put("total_seconds", (f, x, a, kw) -> ((AxonValue.Duration) x).millis() / 1000.0);
        BYTES.put("decode", (f, x, a, kw) -> new String(((PyBytes) x).b, StandardCharsets.UTF_8));
        BYTES.put("hex", (f, x, a, kw) -> java.util.HexFormat.of().formatHex(((PyBytes) x).b));
    }
}
