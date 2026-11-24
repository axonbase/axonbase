package com.axonbase.pl.rt;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.regex.Pattern;

/** Conversao de texto para numeros, como int() e float() do Python. */
public final class NumParse {
    private NumParse() {
    }

    private static final Pattern FLOAT = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?");

    private static String stripUnderscores(String t) {
        if (t.indexOf('_') < 0) {
            return t;
        }
        if (t.startsWith("_") || t.endsWith("_") || t.contains("__") || t.matches(".*[^0-9a-zA-Z]_.*")
                || t.matches(".*_[^0-9a-zA-Z].*")) {
            return null;
        }
        return t.replace("_", "");
    }

    /** int(str, base). */
    public static long parseInt(String s, int base) {
        String t = s.strip();
        String orig = s;
        boolean neg = false;
        String u = stripUnderscores(t);
        if (u == null) {
            throw bad(orig, base);
        }
        t = u;
        if (t.startsWith("+") || t.startsWith("-")) {
            neg = t.startsWith("-");
            t = t.substring(1);
        }
        String low = t.toLowerCase();
        if ((base == 16 || base == 0) && low.startsWith("0x")) {
            base = 16;
            t = t.substring(2);
        } else if ((base == 2 || base == 0) && low.startsWith("0b")) {
            base = 2;
            t = t.substring(2);
        } else if ((base == 8 || base == 0) && low.startsWith("0o")) {
            base = 8;
            t = t.substring(2);
        }
        if (base == 0) {
            base = 10;
        }
        if (t.isEmpty()) {
            throw bad(orig, base);
        }
        for (int i = 0; i < t.length(); i++) {
            if (Character.digit(t.charAt(i), base) < 0) {
                throw bad(orig, base);
            }
        }
        BigInteger b = new BigInteger(t, base);
        if (neg) {
            b = b.negate();
        }
        if (b.bitLength() > 63) {
            throw Errors.overflow64();
        }
        return b.longValue();
    }

    private static RuntimeException bad(String s, int base) {
        return Errors.value("literal invalido para int() na base " + base + ": " + Repr.repr(s));
    }

    /** float(str). */
    public static double parseFloat(String s) {
        String t = s.strip();
        String low = t.toLowerCase();
        switch (low) {
            case "inf", "+inf", "infinity", "+infinity":
                return Double.POSITIVE_INFINITY;
            case "-inf", "-infinity":
                return Double.NEGATIVE_INFINITY;
            case "nan", "+nan", "-nan":
                return Double.NaN;
            default:
                break;
        }
        String u = stripUnderscores(t);
        if (u == null || !FLOAT.matcher(u).matches()) {
            throw Errors.value("nao foi possivel converter a string em float: " + Repr.repr(s));
        }
        return Double.parseDouble(u);
    }

    /** Decimal(str). */
    public static BigDecimal parseDecimal(String s) {
        String t = s.strip();
        String u = stripUnderscores(t);
        if (u == null || !DECIMAL.matcher(u).matches()) {
            throw Errors.value("literal invalido para Decimal: " + Repr.repr(s));
        }
        return new BigDecimal(u);
    }
}
