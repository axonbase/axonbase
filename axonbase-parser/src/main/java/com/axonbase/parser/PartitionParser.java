package com.axonbase.parser;

import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.PartitionCommand;
import com.axonbase.parser.ast.Statement;

/**
 * Sintaxe de partição por tempo e TTL, fora do {@link Parser} para não misturar com o resto da linguagem. As palavras
 * novas são contextuais: continuam valendo como nomes de tabela e de campo.
 */
final class PartitionParser {

    private PartitionParser() {
    }

    /** O que o {@link Parser} oferece a esta classe. */
    interface Tokens {
        boolean word(String word);

        void require(String word);

        String name();

        long duration();

        Expr expression();

        RuntimeException unexpected(String expected);
    }

    /** Depois de {@code DEFINE PARTITION}. */
    static Statement define(Tokens in) {
        String table = onTable(in);
        in.require("field");
        String field = in.name();
        in.require("interval");
        long interval = in.duration();
        Long retention = null;
        Long coldAfter = null;
        while (true) {
            if (in.word("retention")) {
                retention = in.duration();
            } else if (in.word("cold")) {
                in.require("after");
                coldAfter = in.duration();
            } else {
                break;
            }
        }
        return new Statement.Partition(new PartitionCommand.Define(table, field, interval, retention, coldAfter));
    }

    /** Depois de {@code DEFINE TTL}. */
    static Statement defineTtl(Tokens in) {
        String table = onTable(in);
        in.require("field");
        String field = in.name();
        long after = in.word("after") ? in.duration() : 0L;
        return new Statement.Partition(new PartitionCommand.DefineTtl(table, field, after));
    }

    /** Depois de {@code DROP PARTITION}. */
    static Statement drop(Tokens in) {
        String table = onTable(in);
        if (in.word("at")) {
            return new Statement.Partition(new PartitionCommand.Drop(table, in.expression(), null));
        }
        if (in.word("before")) {
            return new Statement.Partition(new PartitionCommand.Drop(table, null, in.expression()));
        }
        throw in.unexpected("AT | BEFORE");
    }

    /** Depois de {@code DROP TTL}. */
    static Statement dropTtl(Tokens in) {
        return new Statement.Partition(new PartitionCommand.DropTtl(onTable(in)));
    }

    /** Depois de {@code ALTER PARTITION}. */
    static Statement alter(Tokens in) {
        String table = onTable(in);
        in.require("at");
        Expr at = in.expression();
        in.require("to");
        if (in.word("cold")) {
            return new Statement.Partition(new PartitionCommand.Move(table, at, true));
        }
        if (in.word("hot")) {
            return new Statement.Partition(new PartitionCommand.Move(table, at, false));
        }
        throw in.unexpected("COLD | HOT");
    }

    /** Depois de {@code SHOW PARTITIONS}. */
    static Statement show(Tokens in) {
        return new Statement.Partition(new PartitionCommand.Show(onTable(in)));
    }

    private static String onTable(Tokens in) {
        in.require("on");
        in.require("table");
        return in.name();
    }
}
