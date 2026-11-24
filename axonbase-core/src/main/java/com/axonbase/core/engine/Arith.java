package com.axonbase.core.engine;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.value.AxonValue;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Aritmética do AxonQL, num só lugar para o operador, as funções {@code math::} e as agregações darem o mesmo
 * resultado.
 *
 * <ul>
 *   <li>Inteiro com inteiro continua inteiro ({@code 10 - 2} é {@code 8}, não decimal). Se o resultado não cabe em
 *       {@code long}, vira decimal exato: nunca há estouro silencioso.</li>
 *   <li>{@code NULL} e {@code NONE} se propagam. Um operando ausente nunca é trocado pelo outro operando.</li>
 *   <li>Operando que não é número é erro claro, e não zero.</li>
 *   <li>Divisão e módulo por zero são erro, e nenhum resultado sai como {@code NaN} ou {@code Infinity}, que não são
 *       JSON válido.</li>
 * </ul>
 */
final class Arith {

    /** Casas da divisão não exata, as mesmas de {@code math::mean}. */
    static final int DIVISION_SCALE = 10;

    /** Acima disso uma potência inteira exata deixa de ser calculada (evita números gigantes). */
    private static final int MAX_EXACT_POW_BITS = 4096;

    private Arith() {
    }

    // ------------------------------------------------------------------
    // operadores
    // ------------------------------------------------------------------

    static AxonValue add(AxonValue l, AxonValue r) {
        if (missing(l) || missing(r)) {
            return AxonValue.nul();
        }
        if (l.isInteger() && r.isInteger()) {
            long a = l.asLong();
            long b = r.asLong();
            long sum = a + b;
            if (((a ^ sum) & (b ^ sum)) < 0) {
                return AxonValue.num(BigDecimal.valueOf(a).add(BigDecimal.valueOf(b)));
            }
            return AxonValue.num(sum);
        }
        return AxonValue.num(dec("+", l).add(dec("+", r)));
    }

    static AxonValue sub(AxonValue l, AxonValue r) {
        if (missing(l) || missing(r)) {
            return AxonValue.nul();
        }
        if (l.isInteger() && r.isInteger()) {
            long a = l.asLong();
            long b = r.asLong();
            long diff = a - b;
            if (((a ^ b) & (a ^ diff)) < 0) {
                return AxonValue.num(BigDecimal.valueOf(a).subtract(BigDecimal.valueOf(b)));
            }
            return AxonValue.num(diff);
        }
        return AxonValue.num(dec("-", l).subtract(dec("-", r)));
    }

    static AxonValue mul(AxonValue l, AxonValue r) {
        if (missing(l) || missing(r)) {
            return AxonValue.nul();
        }
        if (l.isInteger() && r.isInteger()) {
            long a = l.asLong();
            long b = r.asLong();
            long hi = Math.multiplyHigh(a, b);
            long lo = a * b;
            if ((hi == 0 && lo >= 0) || (hi == -1 && lo < 0)) {
                return AxonValue.num(lo);
            }
            return AxonValue.num(BigDecimal.valueOf(a).multiply(BigDecimal.valueOf(b)));
        }
        return AxonValue.num(dec("*", l).multiply(dec("*", r)));
    }

    static AxonValue div(AxonValue l, AxonValue r) {
        if (missing(l) || missing(r)) {
            return AxonValue.nul();
        }
        if (l.isInteger() && r.isInteger()) {
            long a = l.asLong();
            long b = r.asLong();
            if (b == 0) {
                throw divisionByZero();
            }
            if (!(a == Long.MIN_VALUE && b == -1) && a % b == 0) {
                return AxonValue.num(a / b);
            }
        }
        BigDecimal divisor = dec("/", r);
        BigDecimal dividend = dec("/", l);
        if (divisor.signum() == 0) {
            throw divisionByZero();
        }
        return compact(dividend.divide(divisor, DIVISION_SCALE, RoundingMode.HALF_UP));
    }

    static AxonValue mod(AxonValue l, AxonValue r) {
        if (missing(l) || missing(r)) {
            return AxonValue.nul();
        }
        if (l.isInteger() && r.isInteger()) {
            long b = r.asLong();
            if (b == 0) {
                throw divisionByZero();
            }
            return AxonValue.num(b == -1 ? 0 : l.asLong() % b);
        }
        BigDecimal divisor = dec("%", r);
        BigDecimal dividend = dec("%", l);
        if (divisor.signum() == 0) {
            throw divisionByZero();
        }
        return AxonValue.num(dividend.remainder(divisor));
    }

    static AxonValue pow(AxonValue l, AxonValue r) {
        if (missing(l) || missing(r)) {
            return AxonValue.nul();
        }
        BigDecimal base = dec("**", l);
        BigDecimal exp = dec("**", r);
        if (l.isInteger() && r.isInteger() && r.asLong() >= 0) {
            long e = r.asLong();
            long a = l.asLong();
            if (a == 0 || a == 1 || e == 0) {
                return AxonValue.num(e == 0 ? 1 : a == 0 ? 0 : 1);
            }
            int bits = BigInteger.valueOf(a).abs().bitLength();
            if (e <= MAX_EXACT_POW_BITS && (long) bits * e <= MAX_EXACT_POW_BITS) {
                BigInteger exact = BigInteger.valueOf(a).pow((int) e);
                return exact.bitLength() < 64
                    ? AxonValue.num(exact.longValue())
                    : AxonValue.num(new BigDecimal(exact));
            }
        }
        if (base.signum() == 0 && exp.signum() < 0) {
            throw divisionByZero();
        }
        return finite("**", Math.pow(base.doubleValue(), exp.doubleValue()));
    }

    static AxonValue neg(AxonValue v) {
        if (missing(v)) {
            return AxonValue.nul();
        }
        if (v.isInteger()) {
            long a = v.asLong();
            return a == Long.MIN_VALUE
                ? AxonValue.num(BigDecimal.valueOf(a).negate())
                : AxonValue.num(-a);
        }
        return AxonValue.num(dec("-", v).negate());
    }

    /** Texto com {@code +}: usa o conteúdo do texto (sem aspas) e escreve números e demais valores como texto. */
    static AxonValue concat(AxonValue l, AxonValue r) {
        if (missing(l) || missing(r)) {
            return AxonValue.nul();
        }
        return AxonValue.str(text(l) + text(r));
    }

    // ------------------------------------------------------------------
    // agregações e funções math::
    // ------------------------------------------------------------------

    /** Soma acumulada: fica em {@code long} enquanto só há inteiros e vai para decimal exato no estouro. */
    static final class Sum {
        private long whole;
        private BigDecimal wide;
        private boolean integral = true;

        void add(AxonValue v) {
            if (v == null || !v.isNumber()) {
                return;
            }
            if (integral && v.isInteger()) {
                long a = whole;
                long b = v.asLong();
                long sum = a + b;
                if (((a ^ sum) & (b ^ sum)) < 0) {
                    wide = BigDecimal.valueOf(a).add(BigDecimal.valueOf(b));
                    integral = false;
                } else {
                    whole = sum;
                }
                return;
            }
            if (integral) {
                wide = BigDecimal.valueOf(whole);
                integral = false;
            }
            wide = wide.add(v.asDecimal());
        }

        void merge(Sum other) {
            if (other.integral) {
                add(AxonValue.num(other.whole));
            } else {
                if (integral) {
                    wide = BigDecimal.valueOf(whole);
                    integral = false;
                }
                wide = wide.add(other.wide);
            }
        }

        AxonValue result() {
            return integral ? AxonValue.num(whole) : AxonValue.num(wide);
        }

        BigDecimal decimal() {
            return integral ? BigDecimal.valueOf(whole) : wide;
        }
    }

    /** Decimal sem parte fracionária e dentro de {@code long} vira inteiro; o resto continua decimal. */
    static AxonValue compact(BigDecimal value) {
        BigDecimal v = value.stripTrailingZeros();
        if (v.scale() <= 0 && v.compareTo(BigDecimal.valueOf(Long.MIN_VALUE)) >= 0
            && v.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0) {
            return AxonValue.num(v.longValueExact());
        }
        return AxonValue.num(v);
    }

    /** Produto de números: inteiro enquanto couber, decimal exato depois. */
    static AxonValue product(Iterable<AxonValue> values) {
        AxonValue acc = AxonValue.num(1);
        for (AxonValue v : values) {
            if (v != null && v.isNumber()) {
                acc = mul(acc, v);
            }
        }
        return acc;
    }

    static AxonValue abs(AxonValue v) {
        if (missing(v)) {
            return AxonValue.nul();
        }
        if (v.isInteger()) {
            long a = v.asLong();
            return a == Long.MIN_VALUE ? AxonValue.num(BigDecimal.valueOf(a).negate())
                : AxonValue.num(Math.abs(a));
        }
        return AxonValue.num(dec("math::abs", v).abs());
    }

    /** Resultado de função matemática em ponto flutuante: erro claro no lugar de NaN ou Infinity. */
    static AxonValue finite(String operation, double d) {
        if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw AxonError.internal(Messages.get("arith_out_of_range", operation));
        }
        return AxonValue.num(d);
    }

    // ------------------------------------------------------------------
    // apoio
    // ------------------------------------------------------------------

    private static boolean missing(AxonValue v) {
        return v == null || v.isNull() || v.isNone();
    }

    private static String text(AxonValue v) {
        return v.isString() ? v.asString() : v.toString();
    }

    private static BigDecimal dec(String operator, AxonValue v) {
        if (!v.isNumber()) {
            throw AxonError.internal(Messages.get("arith_operand_type", operator, v.type().name().toLowerCase()));
        }
        if (v.isInteger()) {
            return BigDecimal.valueOf(v.asLong());
        }
        try {
            return v.asDecimal();
        } catch (NumberFormatException nanOrInfinity) {
            throw AxonError.internal(Messages.get("arith_out_of_range", operator));
        }
    }

    private static AxonError divisionByZero() {
        return AxonError.internal(Messages.get("stmt_division_by_zero"));
    }
}
