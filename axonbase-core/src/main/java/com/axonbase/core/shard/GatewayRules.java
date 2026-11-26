package com.axonbase.core.shard;

import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/** As regras do que atravessa shards e as checagens comuns aos planejadores. */
final class GatewayRules {

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private GatewayRules() {
    }

    static ShardMap.TableKey key(GatewaySession session, String table) {
        return new ShardMap.TableKey(session.namespace(), session.database(), table);
    }

    static boolean sharded(ShardMap map, GatewaySession session, String table) {
        return map.shardKey(key(session, table)).isPresent();
    }

    static boolean databaseSharded(ShardMap map, GatewaySession session) {
        return session.namespace() != null && session.database() != null
            && map.hasShardedTables(session.namespace(), session.database());
    }

    /** Uma instrução sobre uma tabela dividida não pode ler nada além da própria linha. */
    static void requireSelfContained(Object node, String table) {
        StatementScan scan = StatementScan.of(node);
        if (scan.block()) {
            throw ShardException.refused("cross_block", table);
        }
        if (scan.subquery()) {
            throw ShardException.refused("cross_subquery", table);
        }
        if (scan.graph()) {
            throw ShardException.refused("cross_graph", table);
        }
        if (scan.match()) {
            throw ShardException.refused("cross_search", table);
        }
        if (scan.program()) {
            throw ShardException.refused("cross_program", table);
        }
    }

    /**
     * Numa base com tabelas divididas, o que roda no shard base não pode alcançar dados que estão nos outros: grafo,
     * programas e subconsultas sobre tabelas divididas (ou de origem desconhecida) responderiam com dados faltando.
     */
    static void requireHomeOnly(ShardMap map, GatewaySession session, Object node) {
        if (!databaseSharded(map, session)) {
            return;
        }
        StatementScan scan = StatementScan.of(node);
        if (scan.block()) {
            throw ShardException.refused("cross_block", "-");
        }
        if (scan.graph()) {
            throw ShardException.refused("cross_graph", "-");
        }
        if (scan.program()) {
            throw ShardException.refused("cross_program", "-");
        }
        if (scan.opaqueSubquery()) {
            throw ShardException.refused("cross_subquery", "-");
        }
        for (String table : scan.subqueryTables()) {
            if (sharded(map, session, table)) {
                throw ShardException.refused("cross_subquery", table);
            }
        }
    }

    /** Recusa escritas em slots que estão sendo movidos. */
    static void requireSettled(ShardMap map, Iterable<Integer> slots) {
        for (int slot : slots) {
            if (map.moving(slot)) {
                throw new ShardException(ShardException.Code.MOVING, ShardMessages.get("shard_moving", slot));
            }
        }
    }

    /** Recusa escritas espalhadas, e DDL, enquanto houver qualquer movimentação. */
    static void requireNoMigration(ShardMap map) {
        if (map.migration().isPresent()) {
            throw new ShardException(ShardException.Code.MOVING, ShardMessages.get("shard_migration_running"));
        }
    }

    static int slot(String field, String table, AxonValue value) {
        return "id".equals(field) ? SlotHash.ofId(table, value) : SlotHash.ofValue(value);
    }

    /** Os donos dos slots, na ordem dos shards do mapa. */
    static List<String> owners(ShardMap map, Iterable<Integer> slots) {
        Set<String> found = new LinkedHashSet<>();
        for (int slot : slots) {
            found.add(map.ownerOf(slot));
        }
        List<String> ordered = new ArrayList<>(map.shardIds());
        ordered.retainAll(found);
        return ordered;
    }

    /**
     * Nomes que entram em textos montados pelo gateway (tabela, campo de shard, saga) precisam ser identificadores
     * simples: com crases, a linguagem aceita qualquer texto como nome.
     */
    static void requireIdentifier(String name) {
        if (name == null || !IDENTIFIER.matcher(name).matches()) {
            throw ShardException.refused("identifier", String.valueOf(name));
        }
    }

    /** Um valor de chave que vai ser gravado: um número fracionário mudaria ao ser convertido pelo tipo do campo. */
    static void requireStorableKey(AxonValue value) {
        if (value != null && (value.isNumber() || value.isString()) && fractional(value)) {
            throw ShardException.refused("shard_key_fraction", value.toString());
        }
    }

    private static boolean fractional(AxonValue value) {
        try {
            BigDecimal number = value.isNumber() ? value.asDecimal() : new BigDecimal(value.asString());
            return number.signum() != 0 && number.stripTrailingZeros().scale() > 0;
        } catch (NumberFormatException notANumber) {
            return false;
        }
    }

    /** A tabela tem alguma condição em {@code PERMISSIONS}. */
    static boolean restricts(Statement.Permissions permissions) {
        return permissions != null && (permissions.select() != null || permissions.create() != null
            || permissions.update() != null || permissions.delete() != null);
    }

    /**
     * Um link para outra base lê no shard base, que não tem os registros dos outros: com qualquer tabela dividida no
     * conjunto, não há como saber se a resposta estaria completa.
     */
    static void requireNoLink(ShardMap map, Expr target) {
        Expr table = target instanceof Expr.RecordId id ? id.table() : target;
        if (table instanceof Expr.Qualified && !map.tables().isEmpty()) {
            throw ShardException.refused("cross_source");
        }
    }

    /**
     * Numa base dividida, uma origem ou alvo precisa dizer pelo texto qual tabela toca: o nome, um record id, ou uma
     * variável que guarde um record id de tabela sem {@code SHARD BY}.
     */
    static void requireNamedSource(ShardMap map, GatewayContext context, GatewaySession session, Expr source) {
        requireNoLink(map, source);
        if (!databaseSharded(map, session) || source == null || source instanceof Expr.Ident
            || source instanceof Expr.RecordId id && id.table() instanceof Expr.Ident) {
            return;
        }
        if (source instanceof Expr.Param) {
            AxonValue value = context.evaluate(source, session);
            if (value.isRecordId() && !sharded(map, session, value.asRecordId().table())) {
                return;
            }
        }
        throw ShardException.refused("target_unknown");
    }

    /** O nome aparece na lista de tabelas de um {@code INFO FOR DATABASE}. */
    static boolean mentions(AxonValue database, String table) {
        if (!database.isObject()) {
            return false;
        }
        AxonValue tables = database.asObject().get("tables");
        if (tables == null) {
            return false;
        }
        if (tables.isObject()) {
            return tables.asObject().containsKey(table);
        }
        return tables.isArray() && tables.asArray().stream().anyMatch(t -> t.isString() && t.asString().equals(table));
    }

    /** O literal AxonQL de uma chave de record id (número ou texto). */
    static String keyLiteral(AxonValue key) {
        if (key.isNumber()) {
            return key.asDecimal().toPlainString();
        }
        String text = key.isString() ? key.asString() : key.isUuid() ? key.asUuidValue().toString() : null;
        if (text == null) {
            throw ShardException.refused("record_key_unsupported", key.type().name());
        }
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** O nome do campo que uma atribuição grava, pela mesma regra do motor: o último nome do caminho. */
    static String assignedField(Statement.Assignment assignment) {
        Expr path = assignment.path();
        if (path instanceof Expr.Ident ident) {
            return ident.name();
        }
        if (path instanceof Expr.Idiom idiom) {
            for (int i = idiom.parts().size() - 1; i >= 0; i--) {
                if (idiom.parts().get(i) instanceof Expr.Part.Field field) {
                    return field.name();
                }
            }
        }
        throw ShardException.refused("assignment_unsupported");
    }
}
