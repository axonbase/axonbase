package com.axonbase.pl.rt;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;

/** Mini-linguagem de formatacao (f-strings, format, str.format e operador percent). */
public final class Fmt {
    private Fmt() {
    }

    /** Aplica uma especificacao de formato a um valor. */
    public static String format(Object v, String spec) {
        if (spec.isEmpty()) {
            return Repr.str(v);
        }
        char fill = ' ';
        char align = 0;
        char sign = '-';
        boolean alt = false;
        boolean zero = false;
        int width = -1;
        char group = 0;
        int prec = -1;
        char type = 0;
        int i = 0;
        int n = spec.length();
        if (n >= 2 && "<>^=".indexOf(spec.charAt(1)) >= 0) {
            fill = spec.charAt(0);
            align = spec.charAt(1);
            i = 2;
        } else if (n >= 1 && "<>^=".indexOf(spec.charAt(0)) >= 0) {
            align = spec.charAt(0);
            i = 1;
        }
        if (i < n && "+- ".indexOf(spec.charAt(i)) >= 0) {
            sign = spec.charAt(i++);
        }
        if (i < n && spec.charAt(i) == '#') {
            alt = true;
            i++;
        }
        if (i < n && spec.charAt(i) == '0') {
            zero = true;
            i++;
        }
        int ws = i;
        while (i < n && Character.isDigit(spec.charAt(i))) {
            i++;
        }
        if (i > ws) {
            width = Integer.parseInt(spec.substring(ws, i));
        }
        if (i < n && (spec.charAt(i) == ',' || spec.charAt(i) == '_')) {
            group = spec.charAt(i++);
        }
        if (i < n && spec.charAt(i) == '.') {
            int ps = ++i;
            while (i < n && Character.isDigit(spec.charAt(i))) {
                i++;
            }
            if (i == ps) {
                throw Errors.value("especificacao de formato invalida: faltou a precisao depois do ponto");
            }
            prec = Integer.parseInt(spec.substring(ps, i));
        }
        if (i < n) {
            type = spec.charAt(i++);
        }
        if (i < n) {
            throw Errors.value("especificacao de formato invalida: '" + spec + "'");
        }
        if (width > 10_000_000) {
            throw Errors.value("largura de formato grande demais");
        }
        if (zero && align == 0) {
            fill = '0';
            align = '=';
        }
        boolean isNum = v instanceof Long || v instanceof Double || v instanceof Boolean || v instanceof BigDecimal;
        if (v instanceof String || !isNum) {
            if (type != 0 && type != 's') {
                throw Errors.value("codigo de formato '" + type + "' desconhecido para objeto do tipo '"
                        + Types.nomeDe(v) + "'");
            }
            if (!(v instanceof String) && spec.length() > 0 && type == 0 && align == 0 && width < 0 && prec < 0) {
                return Repr.str(v);
            }
            String s = Repr.str(v);
            if (prec >= 0 && s.length() > prec) {
                s = s.substring(0, prec);
            }
            return pad(s, width, fill, align == 0 || align == '=' ? '<' : align, "", false);
        }
        String body;
        String sg = "";
        if (type == 'c' && (v instanceof Long || v instanceof Boolean)) {
            return pad(new String(Character.toChars((int) Ops.asLong(v))), width, fill, align == 0 ? '<' : align, "", false);
        }
        boolean intLike = v instanceof Long || v instanceof Boolean;
        if (intLike && (type == 0 || type == 'd' || type == 'n' || type == 'b' || type == 'o' || type == 'x' || type == 'X')) {
            if (prec >= 0) {
                throw Errors.value("precisao nao permitida para inteiros");
            }
            long l = Ops.asLong(v);
            BigInteger bi = BigInteger.valueOf(l).abs();
            int radix = type == 'b' ? 2 : type == 'o' ? 8 : (type == 'x' || type == 'X') ? 16 : 10;
            body = bi.toString(radix);
            if (type == 'X') {
                body = body.toUpperCase();
            }
            if (group != 0) {
                body = groupInt(body, group, radix == 10 ? 3 : 4);
            }
            String prefix = alt ? (type == 'b' ? "0b" : type == 'o' ? "0o" : type == 'x' ? "0x" : type == 'X' ? "0X" : "") : "";
            sg = l < 0 ? "-" : sign == '+' ? "+" : sign == ' ' ? " " : "";
            return pad(body, width, fill, align == 0 ? '>' : align, sg + prefix, true);
        }
        if (v instanceof BigDecimal dec) {
            if (type == 0 || type == 'f' || type == 'F' || type == 'g' || type == 'G' || type == 'n') {
                BigDecimal x = dec;
                if (type == 'f' || type == 'F') {
                    x = dec.setScale(prec < 0 ? 6 : prec, RoundingMode.HALF_EVEN);
                    body = x.abs().toPlainString();
                } else if (prec >= 0 && type != 0) {
                    x = dec.round(new MathContext(Math.max(prec, 1), RoundingMode.HALF_EVEN));
                    body = x.abs().toString();
                } else {
                    body = dec.abs().toString();
                }
                body = groupFloat(body, group);
                sg = x.signum() < 0 || (x.signum() == 0 && dec.signum() < 0) ? "-" : sign == '+' ? "+" : sign == ' ' ? " " : "";
                return pad(body, width, fill, align == 0 ? '>' : align, sg, true);
            }
            if (type == '%') {
                BigDecimal x = dec.multiply(BigDecimal.valueOf(100)).setScale(prec < 0 ? 6 : prec, RoundingMode.HALF_EVEN);
                sg = x.signum() < 0 ? "-" : sign == '+' ? "+" : sign == ' ' ? " " : "";
                return pad(groupFloat(x.abs().toPlainString(), group) + "%", width, fill, align == 0 ? '>' : align, sg, true);
            }
            if (type == 'e' || type == 'E') {
                return format(dec.doubleValue(), spec);
            }
            throw Errors.value("codigo de formato '" + type + "' desconhecido para Decimal");
        }
        double d = intLike ? (double) Ops.asLong(v) : (Double) v;
        if (type == 0 && prec < 0 && group == 0) {
            body = Repr.floatRepr(Math.abs(d));
            if (Double.isNaN(d)) {
                body = "nan";
            }
            sg = (d < 0 || (d == 0 && 1 / d < 0)) ? "-" : sign == '+' ? "+" : sign == ' ' ? " " : "";
            return pad(body, width, fill, align == 0 ? '>' : align, sg, true);
        }
        boolean upper = type == 'F' || type == 'E' || type == 'G';
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            body = Double.isNaN(d) ? "nan" : "inf";
            if (upper) {
                body = body.toUpperCase();
            }
            sg = (d < 0) ? "-" : sign == '+' ? "+" : sign == ' ' ? " " : "";
            return pad(body, width, fill == '0' ? ' ' : fill, align == 0 ? '>' : align, sg, true);
        }
        double ad = Math.abs(d);
        boolean neg = d < 0 || (d == 0 && 1 / d < 0);
        char t = type == 0 ? 'g' : Character.toLowerCase(type);
        switch (t) {
            case 'f' -> body = new BigDecimal(ad).setScale(prec < 0 ? 6 : prec, RoundingMode.HALF_EVEN).toPlainString();
            case '%' -> body = new BigDecimal(ad).multiply(BigDecimal.valueOf(100))
                    .setScale(prec < 0 ? 6 : prec, RoundingMode.HALF_EVEN).toPlainString() + "%";
            case 'e' -> body = expFormat(ad, prec < 0 ? 6 : prec);
            case 'g', 'n' -> body = genFormat(ad, prec < 0 ? (type == 0 ? 12 : 6) : Math.max(prec, 1), alt, type == 0);
            default -> throw Errors.value("codigo de formato '" + type + "' desconhecido para float");
        }
        if (alt && prec == 0 && (t == 'f' || t == 'e' || t == '%') && body.indexOf('.') < 0) {
            int ep = body.indexOf('e');
            body = ep >= 0 ? body.substring(0, ep) + "." + body.substring(ep) : t == '%' ? body.replace("%", ".%") : body + ".";
        }
        if (upper) {
            body = body.toUpperCase();
        }
        if (group != 0) {
            body = groupFloat(body, group);
        }
        sg = neg ? "-" : sign == '+' ? "+" : sign == ' ' ? " " : "";
        return pad(body, width, fill, align == 0 ? '>' : align, sg, true);
    }

    private static String expFormat(double ad, int prec) {
        if (ad == 0) {
            return (prec > 0 ? "0." + "0".repeat(prec) : "0") + "e+00";
        }
        BigDecimal bd = new BigDecimal(ad).round(new MathContext(prec + 1, RoundingMode.HALF_EVEN));
        String ds = bd.unscaledValue().toString();
        int exp10 = ds.length() - bd.scale() - 1;
        while (ds.length() < prec + 1) {
            ds += "0";
        }
        ds = ds.substring(0, prec + 1);
        StringBuilder sb = new StringBuilder();
        sb.append(ds.charAt(0));
        if (prec > 0) {
            sb.append('.').append(ds, 1, ds.length());
        }
        sb.append('e').append(exp10 < 0 ? '-' : '+');
        int ae = Math.abs(exp10);
        if (ae < 10) {
            sb.append('0');
        }
        return sb.append(ae).toString();
    }

    private static String genFormat(double ad, int p, boolean alt, boolean emptyType) {
        if (ad == 0) {
            return emptyType ? "0.0" : "0";
        }
        BigDecimal bd = new BigDecimal(ad).round(new MathContext(p, RoundingMode.HALF_EVEN));
        int exp10 = bd.precision() - bd.scale() - 1;
        String r;
        if (exp10 >= -4 && exp10 < p) {
            r = bd.setScale(Math.max(p - 1 - exp10, 0), RoundingMode.HALF_EVEN).toPlainString();
            if (!alt && r.indexOf('.') >= 0) {
                r = r.replaceAll("0+$", "").replaceAll("\\.$", "");
            }
        } else {
            r = expFormat(ad, p - 1);
            if (!alt) {
                int e = r.indexOf('e');
                String m = r.substring(0, e);
                if (m.indexOf('.') >= 0) {
                    m = m.replaceAll("0+$", "").replaceAll("\\.$", "");
                }
                r = m + r.substring(e);
            }
        }
        if (emptyType && r.indexOf('.') < 0 && r.indexOf('e') < 0) {
            r = r + ".0";
        }
        return r;
    }

    private static String groupInt(String digits, char sep, int size) {
        StringBuilder sb = new StringBuilder();
        int n = digits.length();
        for (int i = 0; i < n; i++) {
            if (i > 0 && (n - i) % size == 0) {
                sb.append(sep);
            }
            sb.append(digits.charAt(i));
        }
        return sb.toString();
    }

    private static String groupFloat(String body, char sep) {
        if (sep == 0) {
            return body;
        }
        int end = 0;
        while (end < body.length() && Character.isDigit(body.charAt(end))) {
            end++;
        }
        return groupInt(body.substring(0, end), sep, 3) + body.substring(end);
    }

    private static String pad(String body, int width, char fill, char align, String sign, boolean numeric) {
        int len = body.length() + sign.length();
        if (width <= len) {
            return sign + body;
        }
        int gap = width - len;
        return switch (align) {
            case '<' -> sign + body + String.valueOf(fill).repeat(gap);
            case '^' -> String.valueOf(fill).repeat(gap / 2) + sign + body + String.valueOf(fill).repeat(gap - gap / 2);
            case '=' -> sign + String.valueOf(fill).repeat(gap) + body;
            default -> String.valueOf(fill).repeat(gap) + sign + body;
        };
    }

    // ------------------------------------------------------------------ str.format

    /** Implementa str.format. */
    public static String strFormat(Frame f, String tpl, Object[] args, java.util.Map<String, Object> kw) {
        return strFormat(f, tpl, args, kw, new int[1]);
    }

    private static String strFormat(Frame f, String tpl, Object[] args, java.util.Map<String, Object> kw, int[] auto) {
        StringBuilder sb = new StringBuilder();
        int n = tpl.length();
        for (int i = 0; i < n; i++) {
            char c = tpl.charAt(i);
            if (c == '{') {
                if (i + 1 < n && tpl.charAt(i + 1) == '{') {
                    sb.append('{');
                    i++;
                    continue;
                }
                int depth = 1;
                int j = i + 1;
                while (j < n && depth > 0) {
                    if (tpl.charAt(j) == '{') {
                        depth++;
                    } else if (tpl.charAt(j) == '}') {
                        depth--;
                    }
                    j++;
                }
                if (depth != 0) {
                    throw Errors.value("chave '{' sem fechamento na string de formato");
                }
                String field = tpl.substring(i + 1, j - 1);
                String spec = "";
                String conv = "";
                int colon = field.indexOf(':');
                if (colon >= 0) {
                    spec = field.substring(colon + 1);
                    field = field.substring(0, colon);
                }
                int bang = field.indexOf('!');
                if (bang >= 0) {
                    conv = field.substring(bang + 1);
                    field = field.substring(0, bang);
                }
                Object v;
                String head = field;
                String rest = "";
                int k = 0;
                while (k < field.length() && field.charAt(k) != '.' && field.charAt(k) != '[') {
                    k++;
                }
                head = field.substring(0, k);
                rest = field.substring(k);
                if (head.isEmpty()) {
                    if (auto[0] >= args.length) {
                        throw Errors.index("faltam argumentos posicionais para a string de formato");
                    }
                    v = args[auto[0]++];
                } else if (Character.isDigit(head.charAt(0))) {
                    int ix = Integer.parseInt(head);
                    if (ix >= args.length) {
                        throw Errors.index("indice " + ix + " fora dos argumentos da string de formato");
                    }
                    v = args[ix];
                } else {
                    if (kw == null || !kw.containsKey(head)) {
                        throw Errors.key(head);
                    }
                    v = kw.get(head);
                }
                while (!rest.isEmpty()) {
                    if (rest.charAt(0) == '[') {
                        int e = rest.indexOf(']');
                        String key = rest.substring(1, e);
                        Object kobj = key.matches("-?\\d+") ? (Object) Long.parseLong(key) : key;
                        v = Ops.getitem(f, v, kobj);
                        rest = rest.substring(e + 1);
                    } else {
                        int e = 1;
                        while (e < rest.length() && rest.charAt(e) != '.' && rest.charAt(e) != '[') {
                            e++;
                        }
                        v = Attrs.getattr(f, v, rest.substring(1, e));
                        rest = rest.substring(e);
                    }
                }
                if (conv.equals("r")) {
                    v = Repr.repr(v);
                } else if (conv.equals("s")) {
                    v = Repr.str(v);
                }
                if (spec.indexOf('{') >= 0) {
                    spec = strFormat(f, spec, args, kw, auto);
                }
                sb.append(format(v, spec));
                i = j - 1;
            } else if (c == '}') {
                if (i + 1 < n && tpl.charAt(i + 1) == '}') {
                    sb.append('}');
                    i++;
                } else {
                    throw Errors.value("'}' solto na string de formato");
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Operador percent de strings: "%d %s" % valores. */
    public static String percent(Frame f, String tpl, Object arg) {
        Object[] args = arg instanceof PyTuple t ? t.a : new Object[] {arg};
        StringBuilder sb = new StringBuilder();
        int ai = 0;
        int n = tpl.length();
        for (int i = 0; i < n; i++) {
            char c = tpl.charAt(i);
            if (c != '%') {
                sb.append(c);
                continue;
            }
            i++;
            if (i >= n) {
                throw Errors.value("formato incompleto");
            }
            if (tpl.charAt(i) == '%') {
                sb.append('%');
                continue;
            }
            String flags = "";
            while (i < n && "-+ #0".indexOf(tpl.charAt(i)) >= 0) {
                flags += tpl.charAt(i++);
            }
            int ws = i;
            while (i < n && Character.isDigit(tpl.charAt(i))) {
                i++;
            }
            String width = tpl.substring(ws, i);
            String prec = "";
            if (i < n && tpl.charAt(i) == '.') {
                int ps = ++i;
                while (i < n && Character.isDigit(tpl.charAt(i))) {
                    i++;
                }
                prec = "." + tpl.substring(ps, i);
            }
            if (i >= n) {
                throw Errors.value("formato incompleto");
            }
            char t = tpl.charAt(i);
            if (ai >= args.length) {
                throw Errors.type("argumentos insuficientes para o formato");
            }
            Object v = args[ai++];
            String align = flags.contains("-") ? "<" : ">";
            String sign = flags.contains("+") ? "+" : flags.contains(" ") ? " " : "";
            String zero = flags.contains("0") && !flags.contains("-") ? "0" : "";
            String spec;
            switch (t) {
                case 's' -> sb.append(Fmt.format(Repr.str(v), align + width + prec));
                case 'r' -> sb.append(Fmt.format(Repr.repr(v), align + width + prec));
                case 'd', 'i' -> {
                    long l = v instanceof Double d ? (long) (double) d : Ops.asLong(v);
                    spec = (zero.isEmpty() ? align : "") + sign + zero + width + "d";
                    sb.append(Fmt.format(l, spec));
                }
                case 'f', 'F', 'e', 'E', 'g', 'G' -> {
                    double d = Ops.toDouble(v);
                    spec = (zero.isEmpty() ? align : "") + sign + zero + width + (prec.isEmpty() ? ".6" : prec) + t;
                    sb.append(Fmt.format(d, spec));
                }
                case 'x', 'X', 'o' -> sb.append(Fmt.format(Ops.asLong(v), (zero.isEmpty() ? align : "") + zero + width + t));
                case 'c' -> sb.append(v instanceof String s ? s : new String(Character.toChars((int) Ops.asLong(v))));
                default -> throw Errors.value("caractere de formato '" + t + "' nao suportado");
            }
        }
        if (ai < args.length && !(arg instanceof PyDict)) {
            throw Errors.type("argumentos demais para o formato de string");
        }
        return sb.toString();
    }
}
