package com.axonbase.core.shard;

import com.axonbase.core.engine.Functions;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Quais expressões o gateway pode calcular sozinho. É uma lista do que é permitido: só literais, variáveis que o
 * cliente definiu, operadores e funções que não leem a base, a sessão do shard nem executam instruções. Todo o resto
 * (blocos, subconsultas, {@code $auth}, {@code kv::}, grafo, programas) daria outra resposta fora do shard, ou
 * executaria no gateway sem a autorização que só o shard confere.
 */
final class GatewayExpressions {

    /** Funções do motor que dependem de dados, de provedores externos ou do filtro interno dos shards. */
    private static final List<String> FOREIGN = List.of("kv::", "graph::", "search::", "shard::", "vector::embed",
        "vector::fromstr", "record::exists");

    private static final Set<String> AGGREGATES = Set.of("count", "sum", "avg", "min", "max", Expr.COUNT_DISTINCT);

    private GatewayExpressions() {
    }

    /** A consulta agrupa: o gateway calcula as colunas dos grupos ao fundir as partes. */
    static boolean aggregates(Statement.Select select) {
        return select.grouped() || StatementScan.of(select.fields()).calls().stream().anyMatch(AGGREGATES::contains);
    }

    /** Pode ser calculada sem registro nenhum, com as variáveis {@code vars}. {@code null} vale (cláusula ausente). */
    static boolean local(Expr expr, Set<String> vars) {
        return check(expr, vars, false);
    }

    /** Só literais e coleções de literais: o resultado não passa do tamanho do texto recebido. */
    static boolean literal(Expr expr) {
        return switch (expr) {
            case Expr.Literal value -> true;
            case Expr.ArrayLit array -> array.items().stream().allMatch(GatewayExpressions::literal);
            case Expr.SetLit set -> set.items().stream().allMatch(GatewayExpressions::literal);
            case Expr.ObjectLit object -> object.entries().stream().allMatch(e -> literal(e.value()));
            case Expr.RecordId id -> id.table() instanceof Expr.Ident && literal(id.key());
            default -> false;
        };
    }

    /** Pode ser calculada sobre uma linha já agrupada (colunas, HAVING e ORDER BY de uma agregação espalhada). */
    static boolean overRow(Expr expr, Set<String> vars) {
        return check(expr, vars, true);
    }

    private static boolean check(Expr expr, Set<String> vars, boolean row) {
        return switch (expr) {
            case null -> true;
            case Expr.Literal literal -> true;
            case Expr.Path path -> Functions.constant(path.name()) != null;
            case Expr.Ident ident -> row;
            // $auth e $token são do shard, que conhece a credencial
            case Expr.Param param -> vars.contains(param.name())
                && !com.axonbase.core.Session.isIdentityVariable(param.name());
            case Expr.RecordId id -> id.table() instanceof Expr.Ident && check(id.key(), vars, false);
            case Expr.ArrayLit array -> array.items().stream().allMatch(item -> check(item, vars, row));
            case Expr.SetLit set -> set.items().stream().allMatch(item -> check(item, vars, row));
            case Expr.ObjectLit object -> object.entries().stream().allMatch(e -> check(e.value(), vars, row));
            case Expr.Unary unary -> check(unary.operand(), vars, row);
            case Expr.Cast cast -> check(cast.operand(), vars, row);
            case Expr.Binary binary -> binary.op() != Expr.BinaryOp.MATCH && check(binary.left(), vars, row)
                && check(binary.right(), vars, row);
            case Expr.Range range -> check(range.start(), vars, row) && check(range.end(), vars, row);
            case Expr.Alias alias -> check(alias.expr(), vars, row);
            case Expr.Idiom idiom -> (idiom.base() == null ? row : check(idiom.base(), vars, row))
                && idiom.parts().stream().allMatch(part -> switch (part) {
                    case Expr.Part.Field field -> true;
                    case Expr.Part.All all -> true;
                    case Expr.Part.First first -> true;
                    case Expr.Part.Last last -> true;
                    case Expr.Part.Index index -> check(index.index(), vars, row);
                    default -> false;
                });
            // o argumento de um agregado é calculado no shard; ao fundir, o gateway só vê o acumulado
            case Expr.Call call -> row && AGGREGATES.contains(call.name().toLowerCase(Locale.ROOT))
                || pure(call.name()) && call.args().stream().allMatch(a -> check(a, vars, row));
            default -> false;
        };
    }

    private static boolean pure(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return FOREIGN.stream().noneMatch(lower::startsWith) && Functions.exists(lower);
    }
}
