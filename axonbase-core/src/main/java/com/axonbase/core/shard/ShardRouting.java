package com.axonbase.core.shard;

import com.axonbase.parser.ast.Expr;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Descobre, só pelo texto, quais valores da chave de shard uma condição admite. Vale uma igualdade ou um
 * {@code INSIDE} sobre a chave que esteja em conjunção ({@code AND}) com o resto; qualquer outra forma não restringe e
 * a instrução vai a todos os shards.
 */
final class ShardRouting {

    private ShardRouting() {
    }

    /**
     * @param known    diz se quem chama consegue calcular a expressão
     * @param constant calcula uma expressão que não depende da base (literal, parâmetro, record id, lista)
     * @return os valores admitidos para {@code field}, ou vazio quando a condição não a restringe
     */
    static Optional<List<AxonValue>> restriction(Expr condition, String field, Predicate<Expr> known,
                                                 Function<Expr, AxonValue> constant) {
        if (!(condition instanceof Expr.Binary binary)) {
            return Optional.empty();
        }
        return switch (binary.op()) {
            case AND -> {
                Optional<List<AxonValue>> left = restriction(binary.left(), field, known, constant);
                Optional<List<AxonValue>> right = restriction(binary.right(), field, known, constant);
                // as duas partes valem ao mesmo tempo: serve a mais estreita
                if (left.isEmpty()) {
                    yield right;
                }
                yield right.isEmpty() || left.get().size() <= right.get().size() ? left : right;
            }
            case EQ, EQ_EXACT -> {
                Optional<AxonValue> value = isField(binary.left(), field) ? value(binary.right(), known, constant)
                    : isField(binary.right(), field) ? value(binary.left(), known, constant) : Optional.empty();
                yield value.map(List::of);
            }
            case INSIDE -> isField(binary.left(), field) ? values(binary.right(), known, constant) : Optional.empty();
            default -> Optional.empty();
        };
    }

    private static boolean isField(Expr expr, String field) {
        return expr instanceof Expr.Ident ident && ident.name().equals(field);
    }

    /** A expressão não lê documento nenhum. */
    static boolean isConstant(Expr expr) {
        return switch (expr) {
            case Expr.Literal literal -> true;
            case Expr.Param param -> true;
            case Expr.RecordId id -> id.table() instanceof Expr.Ident && isConstant(id.key());
            case Expr.ArrayLit array -> array.items().stream().allMatch(ShardRouting::isConstant);
            case Expr.Unary unary -> isConstant(unary.operand());
            default -> false;
        };
    }

    private static Optional<AxonValue> value(Expr expr, Predicate<Expr> known, Function<Expr, AxonValue> constant) {
        if (!isConstant(expr) || !known.test(expr)) {
            return Optional.empty();
        }
        AxonValue value = constant.apply(expr);
        return routable(value) ? Optional.of(value) : Optional.empty();
    }

    private static Optional<List<AxonValue>> values(Expr expr, Predicate<Expr> known,
                                                    Function<Expr, AxonValue> constant) {
        if (!isConstant(expr) || !known.test(expr)) {
            return Optional.empty();
        }
        AxonValue list = constant.apply(expr);
        if (list == null || !list.isArray()) {
            return Optional.empty();
        }
        List<AxonValue> out = new ArrayList<>();
        for (AxonValue item : list.asArray()) {
            if (!routable(item)) {
                return Optional.empty();
            }
            out.add(item);
        }
        return Optional.of(out);
    }

    /**
     * Só valores cuja igualdade no motor coincide com a igualdade do hash. Um texto com cara de data fica de fora: o
     * motor pode compará-lo com uma data guardada noutra forma de texto.
     */
    private static boolean routable(AxonValue value) {
        if (value == null) {
            return false;
        }
        return switch (value.type()) {
            case NUMBER, RECORD_ID, UUID -> true;
            case STRING -> !looksLikeInstant(value.asString());
            default -> false;
        };
    }

    private static boolean looksLikeInstant(String text) {
        return text.length() >= 10 && text.charAt(4) == '-' && text.charAt(7) == '-'
            && text.chars().limit(4).allMatch(Character::isDigit);
    }
}
