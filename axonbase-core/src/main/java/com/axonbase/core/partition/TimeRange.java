package com.axonbase.core.partition;

import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Expr.BinaryOp;
import com.axonbase.value.AxonValue;

import java.util.function.Function;

/**
 * Faixa de tempo, em milissegundos e com as pontas inclusas, que um WHERE pode alcançar. Serve para não ler partições
 * que não têm como casar: na dúvida a faixa fica aberta, porque a condição é reavaliada linha a linha.
 */
public record TimeRange(long min, long max) {

    public static final TimeRange ALL = new TimeRange(Long.MIN_VALUE, Long.MAX_VALUE);

    public boolean overlaps(PartitionDescriptor partition) {
        return partition.end() > min && partition.start() <= max;
    }

    private TimeRange atLeast(long value) {
        return new TimeRange(Math.max(min, value), max);
    }

    private TimeRange atMost(long value) {
        return new TimeRange(min, Math.min(max, value));
    }

    /**
     * A faixa de {@code field} permitida pelas partes de {@code cond} ligadas por AND.
     *
     * @param evaluate avalia uma expressão que não depende da linha
     */
    public static TimeRange of(Expr cond, String field, Function<Expr, AxonValue> evaluate) {
        if (cond instanceof Expr.Binary b && b.op() == BinaryOp.AND) {
            TimeRange left = of(b.left(), field, evaluate);
            TimeRange right = of(b.right(), field, evaluate);
            return left.atLeast(right.min).atMost(right.max);
        }
        if (!(cond instanceof Expr.Binary b)) {
            return ALL;
        }
        boolean fieldOnLeft = isField(b.left(), field);
        if (!fieldOnLeft && !isField(b.right(), field)) {
            return ALL;
        }
        BinaryOp op = fieldOnLeft ? b.op() : mirrored(b.op());
        Expr other = fieldOnLeft ? b.right() : b.left();
        boolean lower = op == BinaryOp.GT || op == BinaryOp.GE;
        boolean upper = op == BinaryOp.LT || op == BinaryOp.LE;
        boolean equal = op == BinaryOp.EQ || op == BinaryOp.EQ_EXACT;
        // time::now() anda enquanto a consulta roda: só um limite inferior calculado antes continua seguro
        if (!(lower || upper || equal) || !isConstant(other, lower)) {
            return ALL;
        }
        AxonValue value;
        try {
            value = evaluate.apply(other);
        } catch (RuntimeException notEvaluable) {
            return ALL; // o erro aparece na avaliação linha a linha, como antes
        }
        if (value == null || !value.isDatetime()) {
            return ALL;
        }
        long millis = value.asInstant().toEpochMilli();
        if (lower) {
            return ALL.atLeast(millis);
        }
        return upper ? ALL.atMost(millis) : new TimeRange(millis, millis);
    }

    private static boolean isField(Expr e, String field) {
        return e instanceof Expr.Ident id && id.name().equals(field);
    }

    private static BinaryOp mirrored(BinaryOp op) {
        return switch (op) {
            case GT -> BinaryOp.LT;
            case GE -> BinaryOp.LE;
            case LT -> BinaryOp.GT;
            case LE -> BinaryOp.GE;
            default -> op;
        };
    }

    private static boolean isConstant(Expr e, boolean clockAllowed) {
        return switch (e) {
            case Expr.Literal l -> true;
            case Expr.Param p -> true;
            case Expr.Cast c -> isConstant(c.operand(), clockAllowed);
            case Expr.Binary b -> (b.op() == BinaryOp.ADD || b.op() == BinaryOp.SUB)
                && isConstant(b.left(), clockAllowed) && isConstant(b.right(), clockAllowed);
            case Expr.Call c -> (c.name().equalsIgnoreCase("type::datetime")
                || clockAllowed && c.name().equalsIgnoreCase("time::now"))
                && c.args().stream().allMatch(a -> isConstant(a, clockAllowed));
            default -> false;
        };
    }
}
