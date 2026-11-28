package com.axonbase.parser.ast;

/** Comandos de partição por tempo e de TTL (Time To Live). Durações em milissegundos. */
public sealed interface PartitionCommand {

    /** Tabela alvo. */
    String table();

    /** {@code true} quando o comando não muda estado. */
    default boolean readOnly() {
        return false;
    }

    /**
     * {@code DEFINE PARTITION ON TABLE t FIELD f INTERVAL d [RETENTION d] [COLD AFTER d]}. Retenção e prazo frio
     * nulos significam "nunca".
     */
    record Define(String table, String field, long intervalMillis, Long retentionMillis, Long coldAfterMillis)
        implements PartitionCommand {
    }

    /** {@code DROP PARTITION ON TABLE t AT expr} ou {@code BEFORE expr}: exatamente um dos dois é não nulo. */
    record Drop(String table, Expr at, Expr before) implements PartitionCommand {
    }

    /** {@code ALTER PARTITION ON TABLE t AT expr TO COLD|HOT}. */
    record Move(String table, Expr at, boolean cold) implements PartitionCommand {
    }

    /** {@code SHOW PARTITIONS ON TABLE t}. */
    record Show(String table) implements PartitionCommand {
        @Override
        public boolean readOnly() {
            return true;
        }
    }

    /** {@code DEFINE TTL ON TABLE t FIELD f [AFTER d]}. */
    record DefineTtl(String table, String field, long afterMillis) implements PartitionCommand {
    }

    /** {@code DROP TTL ON TABLE t}. */
    record DropTtl(String table) implements PartitionCommand {
    }
}
