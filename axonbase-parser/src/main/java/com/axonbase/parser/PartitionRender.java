package com.axonbase.parser;

import com.axonbase.parser.ast.PartitionCommand;
import com.axonbase.value.AxonQlText;

/** Texto canônico dos comandos de partição e TTL: é o que o plano de controle grava e reexecuta. */
final class PartitionRender {

    private PartitionRender() {
    }

    static void render(StringBuilder sb, PartitionCommand command) {
        switch (command) {
            case PartitionCommand.Define d -> {
                sb.append("DEFINE PARTITION ON TABLE ").append(AxonQlText.identifier(d.table())).append(" FIELD ").append(AxonQlText.identifier(d.field()))
                    .append(" INTERVAL ").append(duration(d.intervalMillis()));
                if (d.retentionMillis() != null) {
                    sb.append(" RETENTION ").append(duration(d.retentionMillis()));
                }
                if (d.coldAfterMillis() != null) {
                    sb.append(" COLD AFTER ").append(duration(d.coldAfterMillis()));
                }
            }
            case PartitionCommand.Drop d -> {
                sb.append("DROP PARTITION ON TABLE ").append(AxonQlText.identifier(d.table()));
                if (d.at() != null) {
                    sb.append(" AT ").append(Render.expr(d.at()));
                } else {
                    sb.append(" BEFORE ").append(Render.expr(d.before()));
                }
            }
            case PartitionCommand.Move m -> sb.append("ALTER PARTITION ON TABLE ").append(AxonQlText.identifier(m.table()))
                .append(" AT ").append(Render.expr(m.at())).append(m.cold() ? " TO COLD" : " TO HOT");
            case PartitionCommand.Show s -> sb.append("SHOW PARTITIONS ON TABLE ").append(AxonQlText.identifier(s.table()));
            case PartitionCommand.DefineTtl t -> {
                sb.append("DEFINE TTL ON TABLE ").append(AxonQlText.identifier(t.table())).append(" FIELD ").append(AxonQlText.identifier(t.field()));
                if (t.afterMillis() > 0) {
                    sb.append(" AFTER ").append(duration(t.afterMillis()));
                }
            }
            case PartitionCommand.DropTtl t -> sb.append("DROP TTL ON TABLE ").append(AxonQlText.identifier(t.table()));
        }
    }

    /** A maior unidade que representa o valor sem sobra. */
    static String duration(long millis) {
        long[] sizes = {86_400_000L, 3_600_000L, 60_000L, 1_000L};
        String[] units = {"d", "h", "m", "s"};
        for (int i = 0; i < sizes.length; i++) {
            if (millis != 0 && millis % sizes[i] == 0) {
                return (millis / sizes[i]) + units[i];
            }
        }
        return millis + "ms";
    }
}
