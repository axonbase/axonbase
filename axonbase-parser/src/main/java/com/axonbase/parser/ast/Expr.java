package com.axonbase.parser.ast;

import com.axonbase.value.AxonValue;

import java.util.List;

/**
 * Árvore de expressões da AxonQL. Cada nó é um record imutável, espelhando a
 * forma das expressões (vazio, binária, unária, literal, chamada, idioma...).
 */
public sealed interface Expr {

    record Literal(AxonValue value) implements Expr {
    }

    record Ident(String name) implements Expr {
    }

    record Param(String name) implements Expr {
    }

    record Qualified(String link, String table) implements Expr {
    }

    record Path(String name) implements Expr {
    }

    record Call(String name, List<Expr> args) implements Expr {
    }

    record RecordId(Expr table, Expr key) implements Expr {
    }

    record ObjectLit(List<Entry> entries) implements Expr {
        public record Entry(String key, Expr value) {
        }
    }

    record ArrayLit(List<Expr> items) implements Expr {
    }

    record SetLit(List<Expr> items) implements Expr {
    }

    record Block(List<Statement> statements) implements Expr {
    }

    record Unary(UnaryOp op, Expr operand) implements Expr {
    }

    record Binary(BinaryOp op, Expr left, Expr right) implements Expr {
    }

    /** Retorna a AND-encadeada de várias expressões. */
    static Expr and(List<Expr> predicates) {
        Expr result = predicates.get(0);
        for (int i = 1; i < predicates.size(); i++) {
            result = new Expr.Binary(BinaryOp.AND, result, predicates.get(i));
        }
        return result;
    }

    record Cast(String type, Expr operand) implements Expr {
    }

    record Idiom(Expr base, List<Part> parts) implements Expr {
    }

    record Range(Expr start, Expr end, boolean inclusive, boolean skipStart) implements Expr {
    }

    /** Campo com apelido: {@code expr AS nome}. */
    record Alias(Expr expr, String name) implements Expr {
    }

    record SubQuery(Query query) implements Expr {
    }

    // ------------------------------------------------------------------
    // operadores
    // ------------------------------------------------------------------

    enum UnaryOp {
        NOT, NEG, POS
    }

    enum BinaryOp {
        OR,
        AND,
        EQ,
        EQ_EXACT,
        NE,
        LT,
        GT,
        LE,
        GE,
        ADD,
        SUB,
        MUL,
        DIV,
        MOD,
        POW,
        ALL_EQ,
        ANY_EQ,
        COALESCE,
        TERNARY,
        CONTAIN,
        NOT_CONTAIN,
        CONTAIN_ALL,
        CONTAIN_ANY,
        CONTAIN_NONE,
        INSIDE,
        NOT_INSIDE,
        ALL_INSIDE,
        ANY_INSIDE,
        NONE_INSIDE,
        OUTSIDE,
        INTERSECTS,
        MATCH
    }

    // ------------------------------------------------------------------
    // partes de idioma (postfix, incluindo grafo)
    // ------------------------------------------------------------------

    enum Direction {
        OUT, IN, BOTH
    }

    sealed interface Part {

        record Field(String name) implements Part {
        }

        record All() implements Part {
        }

        record First() implements Part {
        }

        record Last() implements Part {
        }

        record Index(Expr index) implements Part {
        }

        record Where(Expr cond) implements Part {
        }

        record Method(String name, List<Expr> args) implements Part {
        }

        record Graph(Direction direction, Expr lookup) implements Part {
        }
    }
}