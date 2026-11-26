package com.axonbase.core.shard;

import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** {@code UPDATE}, {@code UPSERT}, {@code DELETE} e arestas. */
final class MutationPlanner {

    private final GatewayContext context;
    private final CreatePlanner creates;

    MutationPlanner(GatewayContext context, CreatePlanner creates) {
        this.context = context;
        this.creates = creates;
    }

    Plan update(ShardMap map, GatewaySession session, ScriptStatements.Piece piece, Statement.Update update) {
        return mutate(map, session, piece, update.target(), update.cond(), update.data(),
            update.mode() == Statement.UpdateMode.UPSERT);
    }

    Plan delete(ShardMap map, GatewaySession session, ScriptStatements.Piece piece, Statement.Delete delete) {
        return mutate(map, session, piece, delete.target(), delete.cond(), null, false);
    }

    private Plan mutate(ShardMap map, GatewaySession session, ScriptStatements.Piece piece, Expr target,
                        Expr condition, Statement.Data data, boolean upsert) {
        String table = StatementScan.tableOf(target).orElse(null);
        String field = table == null ? null : map.shardKey(GatewayRules.key(session, table)).orElse(null);
        if (field == null) {
            requireUnshardedTarget(map, session, target);
            GatewayRules.requireHomeOnly(map, session, piece.statement());
            return Plan.write(map.home().id(), piece.text());
        }
        GatewayRules.requireSelfContained(piece.statement(), table);
        boolean byRecordId = target instanceof Expr.RecordId;
        List<AxonValue> values = null;
        if (byRecordId && field.equals("id")) {
            values = List.of(context.evaluate(target, session));
        } else {
            Optional<List<AxonValue>> restriction =
                ShardRouting.restriction(condition, field, expr -> context.evaluate(expr, session));
            if (restriction.isPresent()) {
                values = restriction.get();
            }
        }
        List<Integer> slots = values == null ? null
            : values.stream().map(value -> GatewayRules.slot(field, table, value)).toList();
        requireKeyUnchanged(session, table, field, data, values);
        List<String> owners = slots == null ? map.shardIds() : GatewayRules.owners(map, slots);
        if (upsert) {
            owners = List.of(upsertOwner(map, session, table, field, data, byRecordId, slots, owners));
        }
        if (slots == null) {
            GatewayRules.requireNoMigration(map);
        } else {
            GatewayRules.requireSettled(map, slots);
        }
        if (owners.isEmpty()) {
            // nenhum valor admitido: o shard base responde com o vazio que o motor devolveria
            owners = List.of(map.home().id());
        }
        // por record id há no máximo um registro, onde quer que ele esteja
        if (owners.size() > 1 && !byRecordId && session.saga() == null) {
            throw ShardException.refused("multi_shard_write", table, field);
        }
        List<Plan.Part> parts = new ArrayList<>(owners.size());
        owners.forEach(shard -> parts.add(Plan.Part.of(shard, piece.text())));
        return new Plan(parts, MutationPlanner::joined, true);
    }

    /** Junta os registros devolvidos pelos shards, na ordem das chaves, que é a de um nó único. */
    private static AxonValue joined(List<AxonValue> replies) {
        if (replies.size() == 1 || !replies.stream().allMatch(AxonValue::isArray)) {
            return replies.get(0);
        }
        List<AxonValue> all = new ArrayList<>();
        replies.forEach(reply -> all.addAll(reply.asArray()));
        all.sort((a, b) -> idOrder(a).compareTo(idOrder(b)));
        return AxonValue.array(all);
    }

    private static String idOrder(AxonValue record) {
        AxonValue id = record.isObject() ? record.asObject().get("id") : null;
        return id == null ? "" : id.toString();
    }

    private String upsertOwner(ShardMap map, GatewaySession session, String table, String field, Statement.Data data,
                               boolean byRecordId, List<Integer> slots, List<String> owners) {
        if (slots == null || owners.size() != 1) {
            throw ShardException.refused("upsert_single_shard", table, field);
        }
        if (field.equals("id")) {
            if (!byRecordId) {
                // o registro criado ganharia um id novo, que pode ser de outro shard
                throw ShardException.refused("upsert_single_shard", table, field);
            }
            return owners.get(0);
        }
        // o registro criado por um UPSERT vem só dos dados: sem a chave ele ficaria invisível para o roteamento
        AxonValue created = creates.dataField(session, data, field);
        if (created == null || !slots.contains(SlotHash.ofValue(created))) {
            throw ShardException.refused("shard_key_required", table, field);
        }
        return map.ownerOf(SlotHash.ofValue(created));
    }

    private void requireKeyUnchanged(GatewaySession session, String table, String field, Statement.Data data,
                                     List<AxonValue> values) {
        if (data == null) {
            return;
        }
        if (data instanceof Statement.Data.Patch patch) {
            AxonValue operations = context.evaluate(patch.patch(), session);
            if (operations.isArray() && operations.asArray().stream().anyMatch(op -> touches(op, field))) {
                throw ShardException.refused("shard_key_immutable", table, field);
            }
            return;
        }
        boolean replaces = data instanceof Statement.Data.Content || data instanceof Statement.Data.Replace;
        AxonValue next = creates.dataField(session, data, field);
        if (next == null) {
            // CONTENT e REPLACE sem o campo o apagariam; o id é preservado pelo motor
            if (replaces && !field.equals("id")) {
                throw ShardException.refused("shard_key_immutable", table, field);
            }
            return;
        }
        // só vale regravar o mesmo valor que roteou a instrução
        if (values == null || values.size() != 1
            || GatewayRules.slot(field, table, values.get(0)) != GatewayRules.slot(field, table, next)
            || !text(table, field, values.get(0)).equals(text(table, field, next))) {
            throw ShardException.refused("shard_key_immutable", table, field);
        }
    }

    private static String text(String table, String field, AxonValue value) {
        if (field.equals("id")) {
            return SlotHash.idText(table, value);
        }
        return value.isNumber() ? value.asDecimal().stripTrailingZeros().toPlainString()
            : value.isString() ? value.asString() : value.toString();
    }

    private static boolean touches(AxonValue operation, String field) {
        if (!operation.isObject()) {
            return false;
        }
        for (String key : List.of("path", "from")) {
            AxonValue path = operation.asObject().get(key);
            if (path != null && path.isString()) {
                String name = path.asString().replaceFirst("^/", "");
                if (name.equals(field) || name.startsWith(field + "/")) {
                    return true;
                }
            }
        }
        return false;
    }

    Plan relate(ShardMap map, GatewaySession session, ScriptStatements.Piece piece, Expr from, Expr to) {
        if (GatewayRules.databaseSharded(map, session)) {
            for (Expr end : new Expr[] {from, to}) {
                if (end == null) {
                    continue;
                }
                String table = StatementScan.tableOf(end).orElse(null);
                if (table == null || GatewayRules.sharded(map, session, table)) {
                    throw ShardException.refused("cross_relation", table == null ? "-" : table);
                }
            }
        }
        return Plan.write(map.home().id(), piece.text());
    }

    private void requireUnshardedTarget(ShardMap map, GatewaySession session, Expr target) {
        if (target instanceof Expr.Ident || target instanceof Expr.RecordId || target instanceof Expr.Qualified
            || !GatewayRules.databaseSharded(map, session)) {
            return;
        }
        throw ShardException.refused("target_unknown");
    }
}
