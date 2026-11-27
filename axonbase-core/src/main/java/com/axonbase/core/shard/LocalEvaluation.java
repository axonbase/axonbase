package com.axonbase.core.shard;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Executor;
import com.axonbase.parser.ast.Expr;
import com.axonbase.value.AxonValue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * O cálculo de uma expressão no gateway com um orçamento de tamanho por requisição. A expressão é reduzida de dentro
 * para fora: cada operando é medido antes de ser usado, e uma função que multiplica o tamanho dos argumentos (como
 * {@code string::repeat}) é recusada pelo tamanho que teria, antes de ser chamada. O tamanho é contado em caracteres,
 * com um custo fixo por item.
 */
final class LocalEvaluation {

    /** Quanto uma requisição pode produzir ou consumir ao todo: quatro vezes o maior corpo que o gateway aceita. */
    static final long LIMIT = 64L << 20;
    private static final long ITEM = 16;

    private final Executor executor;
    private final Session scope;
    private final GatewaySession session;

    LocalEvaluation(Executor executor, Session scope, GatewaySession session) {
        this.executor = executor;
        this.scope = scope;
        this.session = session;
    }

    AxonValue value(Expr expr) {
        return charged(reduce(expr));
    }

    private AxonValue reduce(Expr expr) {
        return switch (expr) {
            case Expr.Literal literal -> literal.value();
            case Expr.Alias alias -> reduce(alias.expr());
            case Expr.Unary unary -> produced(new Expr.Unary(unary.op(), operand(unary.operand())));
            case Expr.Cast cast -> produced(new Expr.Cast(cast.type(), operand(cast.operand())));
            case Expr.Binary binary -> produced(
                new Expr.Binary(binary.op(), operand(binary.left()), operand(binary.right())));
            // coleções só guardam referências: são medidas quando usadas ou devolvidas
            case Expr.ArrayLit array -> evaluate(new Expr.ArrayLit(array.items().stream().map(this::inner).toList()));
            case Expr.SetLit set -> evaluate(new Expr.SetLit(set.items().stream().map(this::inner).toList()));
            case Expr.ObjectLit object -> evaluate(new Expr.ObjectLit(object.entries().stream()
                .map(e -> new Expr.ObjectLit.Entry(e.key(), inner(e.value()))).toList()));
            case Expr.RecordId id -> evaluate(new Expr.RecordId(id.table(), inner(id.key())));
            case Expr.Idiom idiom when idiom.base() != null -> produced(new Expr.Idiom(operand(idiom.base()),
                idiom.parts().stream().map(this::part).toList()));
            case Expr.Call call -> call(call);
            default -> evaluate(expr);
        };
    }

    private Expr.Part part(Expr.Part part) {
        return part instanceof Expr.Part.Index index ? new Expr.Part.Index(inner(index.index())) : part;
    }

    private Expr inner(Expr expr) {
        return new Expr.Literal(reduce(expr));
    }

    /** Um valor que vai ser consumido por uma operação. */
    private Expr operand(Expr expr) {
        return new Expr.Literal(charged(reduce(expr)));
    }

    private AxonValue call(Expr.Call call) {
        List<AxonValue> args = call.args().stream().map(argument -> charged(reduce(argument))).toList();
        require(growth(call.name().toLowerCase(Locale.ROOT), args));
        return produced(new Expr.Call(call.name(),
            args.stream().map(argument -> (Expr) new Expr.Literal(argument)).toList()));
    }

    private AxonValue produced(Expr expr) {
        return charged(evaluate(expr));
    }

    private AxonValue evaluate(Expr expr) {
        return executor.evaluate(expr, scope);
    }

    private AxonValue charged(AxonValue value) {
        long size = size(value, LIMIT - session.spent());
        require(size);
        session.spend(size);
        return value;
    }

    private void require(long size) {
        if (size > LIMIT - session.spent()) {
            throw ShardException.refused("local_too_large", LIMIT);
        }
    }

    /** O tamanho que o resultado teria nas funções que podem ser muito maiores que os argumentos. */
    private static long growth(String name, List<AxonValue> args) {
        return switch (name) {
            case "string::repeat" -> times(length(args, 0), count(args, 1, 0));
            case "string::pad_start", "string::pad_end" -> count(args, 1, 0);
            case "rand::string" -> args.isEmpty() ? 32 : count(args, 0, 0);
            case "math::fixed" -> Math.abs((long) integer(args, 1));
            case "string::replace" -> times(length(args, 0) / Math.max(1, length(args, 1)) + 1, length(args, 2) + 1);
            case "string::split", "string::words" -> times(length(args, 0) + 1, ITEM);
            case "string::join", "array::join" -> {
                AxonValue parts = args.size() == 2 ? args.get(1) : null;
                long items = parts != null && parts.isArray() ? parts.asArray().size()
                    : parts != null && parts.isSet() ? parts.asSet().size() : args.size();
                yield times(length(args, 0), items);
            }
            default -> 0;
        };
    }

    private static long times(long a, long b) {
        return a != 0 && b > Long.MAX_VALUE / a ? Long.MAX_VALUE : a * b;
    }

    private static long length(List<AxonValue> args, int index) {
        if (index >= args.size()) {
            return 0;
        }
        AxonValue value = args.get(index);
        if (value.isNone() || value.isNull()) {
            return 0;
        }
        return value.isString() ? value.asString().length() : size(value, LIMIT);
    }

    /** O mesmo inteiro que a função vai ler do argumento, inclusive o corte para 32 bits. */
    private static int integer(List<AxonValue> args, int index) {
        if (index >= args.size()) {
            return 0;
        }
        AxonValue value = args.get(index);
        if (value.isNumber()) {
            return (int) value.asLong();
        }
        try {
            return value.isString() ? (int) Long.parseLong(value.asString().trim()) : 0;
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    private static long count(List<AxonValue> args, int index, long minimum) {
        return Math.max(minimum, integer(args, index));
    }

    /** Tamanho aproximado de um valor; para de contar assim que passa de {@code cap}. */
    private static long size(AxonValue value, long cap) {
        long total = ITEM;
        switch (value.type()) {
            case STRING -> total += value.asString().length();
            case TABLE -> total += value.asTable().length();
            case NUMBER -> {
                if (value.raw() instanceof BigDecimal decimal) {
                    total += decimal.precision() + Math.abs((long) decimal.scale());
                }
            }
            case ARRAY, SET -> {
                for (AxonValue item : value.isArray() ? value.asArray() : value.asSet()) {
                    total += size(item, cap - total);
                    if (total > cap) {
                        break;
                    }
                }
            }
            case OBJECT -> {
                for (Map.Entry<String, AxonValue> entry : value.asObject().entrySet()) {
                    total += entry.getKey().length() + size(entry.getValue(), cap - total);
                    if (total > cap) {
                        break;
                    }
                }
            }
            case BYTES -> total += ((byte[]) value.raw()).length;
            case RECORD_ID -> {
                Object key = value.asRecordId().key();
                total += value.asRecordId().table().length()
                    + (key instanceof AxonValue nested ? size(nested, cap - total) : String.valueOf(key).length());
            }
            default -> {
                // tipos de tamanho fixo
            }
        }
        return total;
    }
}
