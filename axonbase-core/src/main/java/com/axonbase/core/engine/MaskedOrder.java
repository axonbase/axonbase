package com.axonbase.core.engine;

import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;

import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;

/**
 * Se um {@code SELECT} em modo parcial pode responder a um usuário com colunas mascaradas. A resposta parcial leva, por
 * linha, a chave do registro e os valores do {@code ORDER BY} calculados sobre o documento sem máscara. A conferência
 * olha só o texto da consulta (não depende dos dados) e é uma lista do que é permitido: o que ela não reconhece é
 * recusado.
 */
final class MaskedOrder {

    /** Funções que leem a base, a busca ou serviços externos, e não só os argumentos. */
    private static final List<String> OPAQUE = List.of("kv::", "graph::", "search::", "shard::", "vector::embed",
        "vector::fromstr");

    private MaskedOrder() {
    }

    /**
     * @param resolved os termos do ORDER BY com os apelidos já trocados pelas expressões
     * @param masked   se o nome de uma coluna está mascarado para o usuário
     */
    static boolean safe(Statement.Select select, List<Statement.OrderTerm> resolved, Predicate<String> masked) {
        // a chave do registro vai na resposta, e numa junção a máscara não alcança as colunas de cada lado
        if (masked.test("id") || !select.joins().isEmpty()) {
            return false;
        }
        // ordenar pelo nome de uma coluna de saída mascarada exporia o que ela esconde, venha de onde vier
        boolean namesMasked = select.orders().stream()
            .anyMatch(term -> term.field() instanceof Expr.Ident id && masked.test(id.name()));
        return !namesMasked && resolved.stream().allMatch(term -> free(term.field(), masked));
    }

    private static boolean free(Expr expr, Predicate<String> masked) {
        return switch (expr) {
            case null -> true;
            case Expr.Literal literal -> true;
            case Expr.Param param -> true;
            case Expr.Path path -> Functions.constant(path.name()) != null;
            case Expr.Ident ident -> !ident.name().equals("*") && !masked.test(ident.name());
            case Expr.ArrayLit array -> array.items().stream().allMatch(item -> free(item, masked));
            case Expr.SetLit set -> set.items().stream().allMatch(item -> free(item, masked));
            case Expr.ObjectLit object -> object.entries().stream().allMatch(e -> free(e.value(), masked));
            case Expr.Unary unary -> free(unary.operand(), masked);
            case Expr.Cast cast -> free(cast.operand(), masked);
            case Expr.Binary binary -> free(binary.left(), masked) && free(binary.right(), masked);
            case Expr.Alias alias -> free(alias.expr(), masked);
            // um idioma sem partes, ou com partes como * sobre um campo ausente, devolve o documento inteiro
            case Expr.Idiom idiom -> !idiom.parts().isEmpty() && free(idiom.base(), masked)
                && idiom.parts().stream().allMatch(part -> switch (part) {
                    case Expr.Part.Field field -> !masked.test(field.name());
                    case Expr.Part.Index index -> free(index.index(), masked);
                    default -> false;
                });
            case Expr.Call call -> pure(call.name())
                && call.args().stream().allMatch(argument -> free(argument, masked));
            default -> false;
        };
    }

    private static boolean pure(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return OPAQUE.stream().noneMatch(lower::startsWith) && Functions.exists(lower);
    }
}
