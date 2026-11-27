package com.axonbase.core.shard;

import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Definições: repetidas em todos os shards, um de cada vez, para o catálogo ser o mesmo em qualquer um. O
 * {@code SHARD BY} entra no mapa do plano de controle depois de todos aceitarem a definição.
 */
final class DdlPlanner {

    /** Tipos cuja conversão não muda o slot: o hash trata número e texto numérico como iguais. */
    private static final Set<String> KEY_TYPES = Set.of("", "any", "string", "int", "number", "decimal");
    private static final List<String> CHECKED_DEFINITIONS =
        List.of("DEFINE EVENT", "CREATE EVENT", "CREATE DATA RULE", "DEFINE PARTITION", "DEFINE TTL");

    private final GatewayContext context;
    private final ShardMapStore store;
    private final ShardMapCache maps;

    DdlPlanner(GatewayContext context, ShardMapStore store, ShardMapCache maps) {
        this.context = context;
        this.store = store.stampedBy(context.clock);
        this.maps = maps;
    }

    static boolean isDefinition(Statement statement) {
        return statement instanceof Statement.DefineTable || statement instanceof Statement.CreateTable
            || statement instanceof Statement.DefineField || statement instanceof Statement.DefineIndex
            || statement instanceof Statement.CreateIndex || statement instanceof Statement.DropIndex
            || statement instanceof Statement.DefineAnalyzer || statement instanceof Statement.CreateAnalyzer
            || statement instanceof Statement.DropAnalyzer || statement instanceof Statement.DefineEmbedding
            || statement instanceof Statement.DropEmbedding || statement instanceof Statement.Reembed
            || statement instanceof Statement.DefineEvent || statement instanceof Statement.CreateEvent
            || statement instanceof Statement.DropEvent || statement instanceof Statement.DefineUser
            || statement instanceof Statement.CreateUser || statement instanceof Statement.DropUser
            || statement instanceof Statement.DefineAccess || statement instanceof Statement.GrantAccess
            || statement instanceof Statement.RevokeAccess || statement instanceof Statement.DefineDatabaseLink
            || statement instanceof Statement.CreateDatabaseLink || statement instanceof Statement.AlterDatabaseLink
            || statement instanceof Statement.DropDatabaseLink || statement instanceof Statement.CreateSaga
            || statement instanceof Statement.DropSaga || statement instanceof Statement.CreateDataRule
            || statement instanceof Statement.DropDataRule || statement instanceof Statement.DefineAiAudit
            || statement instanceof Statement.DropAiAudit || statement instanceof Statement.RemoveTable
            || statement instanceof Statement.DropTable || statement instanceof Statement.AlterTable
            || statement instanceof Statement.CreateFunction || statement instanceof Statement.CreateProcedure
            || statement instanceof Statement.CreatePackage || statement instanceof Statement.DropProgram
            || statement instanceof Statement.CreateJks;
    }

    AxonValue run(ShardMap map, GatewaySession session, ScriptStatements.Piece piece) {
        GatewayRules.requireNoMigration(map);
        Statement statement = piece.statement();
        check(map, session, statement);
        AxonValue result = broadcast(map, session, piece.text());
        record(session, statement);
        return result;
    }

    private void check(ShardMap map, GatewaySession session, Statement statement) {
        switch (statement) {
            case Statement.DefineTable table -> checkTable(map, session, table);
            case Statement.DefineIndex index -> checkIndex(map, session, index.name(), index.table(), index.columns(),
                index.unique());
            case Statement.CreateIndex index -> checkIndex(map, session, index.name(), index.table(), index.columns(),
                index.unique());
            case Statement.DefineEvent event ->
                checkEvent(map, session, event.table(), event.when(), event.then());
            case Statement.CreateEvent event ->
                checkEvent(map, session, event.table(), event.when(), event.then());
            case Statement.DefineField field -> {
                checkColumn(map, session, field.table(), field.name(), field.type(), field.valueExpr() != null);
                checkReference(map, session, field.table(), field.references());
            }
            case Statement.CreateTable table -> {
                table.columns().forEach(column ->
                    checkColumn(map, session, table.name(), column.name(), column.type(), false));
                // a chave primária só é conferida dentro do shard: precisa conter a chave de divisão
                List<String> key = table.columns().stream().filter(Statement.ColumnDef::primaryKey)
                    .map(Statement.ColumnDef::name).toList();
                if (!key.isEmpty() && !key.contains("id")) {
                    checkIndex(map, session, "PRIMARY KEY", table.name(), key, true);
                }
            }
            case Statement.AlterTable alter -> alter.operations()
                .forEach(operation -> checkOperation(map, session, alter.name(), operation));
            case Statement.CreateDataRule rule -> keys(map, session).forEach((table, key) ->
                requireKeyOutsideRule(rule, key));
            default -> {
            }
        }
    }

    private void checkTable(ShardMap map, GatewaySession session, Statement.DefineTable table) {
        String current = map.shardKey(GatewayRules.key(session, table.name())).orElse(null);
        if (GatewayRules.restricts(table.permissions()) && (current != null || table.shardBy() != null)) {
            throw ShardException.refused("sharded_permissions", table.name());
        }
        if (current != null) {
            if (!current.equals(table.shardBy())) {
                throw ShardException.refused("shard_key_fixed", table.name(), current);
            }
            return;
        }
        if (table.shardBy() == null) {
            return;
        }
        if (session.namespace() == null || session.database() == null) {
            throw ShardException.refused("scope_required");
        }
        GatewayRules.requireIdentifier(table.name());
        GatewayRules.requireIdentifier(table.shardBy());
        // as definições primeiro: PERMISSIONS esconderiam registros da conferência de tabela vazia
        checkExistingDefinitions(map, session, table.name(), table.shardBy());
        for (String shard : map.shardIds()) {
            // só uma tabela conhecida pode ter registros; consultar uma desconhecida a criaria
            AxonValue database = context.sendSystem(map, shard, session, "INFO FOR DATABASE");
            if (!GatewayRules.mentions(database, table.name())) {
                continue;
            }
            AxonValue rows = context.sendSystem(map, shard, session, "SELECT * FROM " + table.name() + " LIMIT 1");
            if (rows.isArray() && !rows.asArray().isEmpty()) {
                throw ShardException.refused("shard_table_not_empty", table.name());
            }
        }
    }

    /** Definições anteriores que passariam a valer só dentro de um shard. */
    private void checkExistingDefinitions(ShardMap map, GatewaySession session, String table, String field) {
        String home = map.home().id();
        checkOtherDefinitions(map, session, table, field);
        AxonValue database = context.sendSystem(map, home, session, "INFO FOR DATABASE");
        if (!GatewayRules.mentions(database, table)) {
            return;
        }
        if (context.sendSystem(map, home, session, ShardRequest.PERMISSIONS,
            Map.of("table", AxonValue.str(table))).asBool()) {
            throw ShardException.refused("sharded_permissions", table);
        }
        AxonValue info = context.sendSystem(map, home, session, "INFO FOR TABLE " + table);
        Map<String, AxonValue> out = info.asObject();
        AxonValue indexes = out.get("indexes");
        if (indexes != null && indexes.isObject()) {
            indexes.asObject().forEach((name, index) -> {
                AxonValue unique = index.asObject().get("unique");
                AxonValue columns = index.asObject().get("columns");
                if (unique != null && unique.asBool()
                    && columns.asArray().stream().noneMatch(c -> c.asString().equals(field))) {
                    throw ShardException.refused("unique_without_key", name, field);
                }
            });
        }
        AxonValue events = out.get("events");
        if (events != null && events.isObject() && !events.asObject().isEmpty()) {
            throw ShardException.refused("cross_event", table);
        }
        AxonValue fields = out.get("fields");
        AxonValue key = fields == null || !fields.isObject() ? null : fields.asObject().get(field);
        if (key != null) {
            AxonValue type = key.asObject().get("type");
            requireKeyType(table, field, type == null || !type.isString() ? null : type.asString());
        }
    }

    /** Eventos de qualquer tabela que citem a nova, Data Rules que usem a chave dela, e partição ou TTL dela. */
    private void checkOtherDefinitions(ShardMap map, GatewaySession session, String table, String field) {
        AxonValue definitions = context.sendSystem(map, map.home().id(), session, ShardRequest.DEFINITIONS);
        for (AxonValue item : definitions.asArray()) {
            Map<String, AxonValue> command = item.asObject();
            String text = command.get("definition").asString();
            if (command.get("ns").asString().equals(session.namespace())
                && command.get("db").asString().equals(session.database())) {
                requireKeyInPrimaryKey(text, table, field);
            }
            if (!command.get("ns").asString().equals(session.namespace())
                || !command.get("db").asString().equals(session.database())
                || CHECKED_DEFINITIONS.stream().noneMatch(p -> text.regionMatches(true, 0, p, 0, p.length()))) {
                continue;
            }
            for (Statement statement : AxonQl.parse(text).statements()) {
                if (statement instanceof Statement.CreateDataRule rule) {
                    requireKeyOutsideRule(rule, field);
                } else if (statement instanceof Statement.Partition partition) {
                    // a regra existe só no shard base: os outros shards ficariam sem ela
                    if (partition.command().table().equals(table)) {
                        throw ShardException.refused("sharded_partition", table);
                    }
                } else if (StatementScan.of(statement).identifiers().contains(table)) {
                    throw ShardException.refused("cross_event", table);
                }
            }
        }
    }

    /** Uma chave primária já declarada na tabela precisa conter a chave de divisão, como um índice UNIQUE. */
    private static void requireKeyInPrimaryKey(String definition, String table, String field) {
        if (!definition.startsWith("CREATE TABLE ")) {
            return;
        }
        if (AxonQl.parse(definition).statements().get(0) instanceof Statement.CreateTable created
            && created.name().equals(table)) {
            List<String> key = created.columns().stream().filter(Statement.ColumnDef::primaryKey)
                .map(Statement.ColumnDef::name).toList();
            if (!key.isEmpty() && !key.contains("id") && !key.contains(field)) {
                throw ShardException.refused("unique_without_key", "PRIMARY KEY", field);
            }
        }
    }

    /** As chaves das tabelas divididas da base da sessão. */
    private static Map<String, String> keys(ShardMap map, GatewaySession session) {
        Map<String, String> keys = new LinkedHashMap<>();
        map.tables().forEach((table, key) -> {
            if (table.namespace().equals(session.namespace()) && table.database().equals(session.database())) {
                keys.put(table.table(), key);
            }
        });
        return keys;
    }

    /** Uma Data Rule grava na criação os valores que compara: na chave, mudaria o shard do registro. */
    private static void requireKeyOutsideRule(Statement.CreateDataRule rule, String key) {
        if (StatementScan.of(rule.predicate()).identifiers().contains(key)) {
            throw ShardException.refused("shard_key_rule", rule.name(), key);
        }
    }

    private static void requireKeyType(String table, String field, String type) {
        String name = type == null ? "" : type.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("option<") && name.endsWith(">")) {
            name = name.substring("option<".length(), name.length() - 1);
        }
        if (!KEY_TYPES.contains(name)) {
            throw ShardException.refused("shard_key_type", table, field);
        }
    }

    private void checkColumn(ShardMap map, GatewaySession session, String table, String column, String type,
                             boolean computed) {
        String key = map.shardKey(GatewayRules.key(session, table)).orElse(null);
        if (key == null || !key.equals(column)) {
            return;
        }
        if (computed) {
            throw ShardException.refused("shard_key_type", table, column);
        }
        requireKeyType(table, column, type);
    }

    private void checkOperation(ShardMap map, GatewaySession session, String table, Statement.AlterOp operation) {
        switch (operation) {
            case Statement.AddColumn add -> {
                if (add.primaryKey()) {
                    checkIndex(map, session, "PRIMARY KEY", table, List.of(add.name()), true);
                }
                checkColumn(map, session, table, add.name(), add.type(), false);
                checkReference(map, session, table, add.references());
            }
            case Statement.ModifyColumn modify -> checkColumn(map, session, table, modify.name(), modify.type(), false);
            case Statement.DropColumn drop -> {
                if (map.shardKey(GatewayRules.key(session, table)).filter(drop.name()::equals).isPresent()) {
                    throw ShardException.refused("shard_key_immutable", table, drop.name());
                }
            }
        }
    }

    private void checkReference(ShardMap map, GatewaySession session, String table, String references) {
        if (references != null) {
            refuseOnSharded(map, session, table, "cross_reference");
            refuseOnSharded(map, session, references, "cross_reference");
        }
    }

    /** Um evento roda no shard de quem o disparou: não pode estar numa tabela dividida nem escrever numa. */
    private void checkEvent(ShardMap map, GatewaySession session, String table, Expr when, List<Statement> then) {
        refuseOnSharded(map, session, table, "cross_event");
        if (!GatewayRules.databaseSharded(map, session)) {
            return;
        }
        StatementScan scan = StatementScan.of(then);
        Set<String> named = new LinkedHashSet<>(scan.identifiers());
        named.addAll(StatementScan.of(Arrays.asList(when)).identifiers());
        for (String sharded : keys(map, session).keySet()) {
            if (named.contains(sharded)) {
                throw ShardException.refused("cross_event", sharded);
            }
        }
        if (scan.unnamedTarget()) {
            throw ShardException.refused("target_unknown");
        }
    }

    private void checkIndex(ShardMap map, GatewaySession session, String name, String table, List<String> columns,
                            boolean unique) {
        String field = map.shardKey(GatewayRules.key(session, table)).orElse(null);
        if (field != null && unique && !columns.contains(field)) {
            throw ShardException.refused("unique_without_key", name, field);
        }
    }

    private void refuseOnSharded(ShardMap map, GatewaySession session, String table, String message) {
        if (GatewayRules.sharded(map, session, table)) {
            throw ShardException.refused(message, table);
        }
    }

    /** Repete em todos os shards, começando pelo base. Uma falha no meio diz quem já aplicou. */
    private AxonValue broadcast(ShardMap map, GatewaySession session, String text) {
        List<String> done = new ArrayList<>();
        AxonValue result = null;
        for (String shard : map.shardIds()) {
            try {
                AxonValue reply = context.send(map, Plan.Part.of(shard, text), session, false);
                if (result == null) {
                    result = reply;
                }
                done.add(shard);
            } catch (RuntimeException e) {
                if (done.isEmpty()) {
                    throw e;
                }
                throw new ShardException(ShardException.Code.PARTIAL_WRITE,
                    ShardMessages.get("partial_definition", done, shard, e.getMessage()), e);
            }
        }
        return result;
    }

    private void record(GatewaySession session, Statement statement) {
        if (statement instanceof Statement.DefineTable table && table.shardBy() != null) {
            ShardMap.TableKey key = GatewayRules.key(session, table.name());
            if (maps.get().shardKey(key).isEmpty()) {
                store.update(map -> map.shardKey(key).isPresent() ? map : map.withTable(key, table.shardBy()));
                maps.reload();
            }
            return;
        }
        String removed = statement instanceof Statement.RemoveTable remove ? remove.name()
            : statement instanceof Statement.DropTable drop ? drop.name() : null;
        if (removed != null && GatewayRules.sharded(maps.get(), session, removed)) {
            ShardMap.TableKey key = GatewayRules.key(session, removed);
            store.update(map -> map.shardKey(key).isEmpty() ? map : map.withoutTable(key));
            maps.reload();
        }
    }
}
