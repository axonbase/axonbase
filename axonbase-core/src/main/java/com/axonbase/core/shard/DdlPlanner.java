package com.axonbase.core.shard;

import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Definições: repetidas em todos os shards, um de cada vez, para o catálogo ser o mesmo em qualquer um. O
 * {@code SHARD BY} entra no mapa do plano de controle depois de todos aceitarem a definição.
 */
final class DdlPlanner {

    private static final Pattern FIELD = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final GatewayContext context;
    private final ShardMapStore store;
    private final ShardMapCache maps;

    DdlPlanner(GatewayContext context, ShardMapStore store, ShardMapCache maps) {
        this.context = context;
        this.store = store;
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
            case Statement.DefineEvent event -> refuseOnSharded(map, session, event.table(), "cross_event");
            case Statement.CreateEvent event -> refuseOnSharded(map, session, event.table(), "cross_event");
            case Statement.DefineField field -> {
                if (field.references() != null) {
                    refuseOnSharded(map, session, field.table(), "cross_reference");
                }
            }
            default -> {
            }
        }
    }

    private void checkTable(ShardMap map, GatewaySession session, Statement.DefineTable table) {
        String current = map.shardKey(GatewayRules.key(session, table.name())).orElse(null);
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
        if (!FIELD.matcher(table.shardBy()).matches()) {
            throw ShardException.refused("shard_key_name", table.shardBy());
        }
        for (String shard : map.shardIds()) {
            // só uma tabela conhecida pode ter registros; consultar uma desconhecida a criaria
            AxonValue database = context.send(map, Plan.Part.of(shard, "INFO FOR DATABASE"), session, false);
            if (!mentions(database, table.name())) {
                continue;
            }
            AxonValue rows = context.send(map,
                Plan.Part.of(shard, "SELECT * FROM " + table.name() + " LIMIT 1"), session, false);
            if (rows.isArray() && !rows.asArray().isEmpty()) {
                throw ShardException.refused("shard_table_not_empty", table.name());
            }
        }
        checkExistingDefinitions(map, session, table.name(), table.shardBy());
    }

    /** Definições anteriores que passariam a valer só dentro de um shard. */
    private void checkExistingDefinitions(ShardMap map, GatewaySession session, String table, String field) {
        AxonValue database = context.send(map, Plan.Part.of(map.home().id(), "INFO FOR DATABASE"), session, false);
        if (!mentions(database, table)) {
            return;
        }
        AxonValue info = context.send(map, Plan.Part.of(map.home().id(), "INFO FOR TABLE " + table), session, false);
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
    }

    /** O nome da tabela aparece em {@code INFO FOR DATABASE}, em qualquer das formas que ele usa. */
    private static boolean mentions(AxonValue database, String table) {
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
