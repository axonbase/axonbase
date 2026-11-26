package com.axonbase.core.shard;

import com.axonbase.core.engine.ShardPartial;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** {@code SELECT} e {@code EXPLAIN}: um shard quando a chave está na consulta, todos quando não está. */
final class ReadPlanner {

    private final GatewayContext context;

    ReadPlanner(GatewayContext context) {
        this.context = context;
    }

    /** Os shards que uma consulta sobre tabela dividida precisa ouvir. */
    private record Route(String table, String field, List<String> shards) {
    }

    Plan select(ShardMap map, GatewaySession session, ScriptStatements.Piece piece, Statement.Select select) {
        Optional<Route> route = route(map, session, select);
        if (route.isEmpty()) {
            return Plan.read(map.home().id(), piece.text());
        }
        List<String> shards = route.get().shards();
        if (shards.size() == 1) {
            return Plan.read(shards.get(0), piece.text());
        }
        return scatter(map, session, piece, select, route.get());
    }

    /** O plano de roteamento, no lugar do plano de execução de um shard. */
    Plan explain(ShardMap map, GatewaySession session, ScriptStatements.Piece piece, Statement.Explain explain) {
        Optional<Route> route = route(map, session, explain.select());
        if (route.isEmpty()) {
            return Plan.read(map.home().id(), piece.text());
        }
        Map<String, AxonValue> out = new LinkedHashMap<>();
        out.put("routing", AxonValue.str(route.get().shards().size() == 1 ? "single" : "scatter"));
        out.put("table", AxonValue.str(route.get().table()));
        out.put("shard_key", AxonValue.str(route.get().field()));
        out.put("shards", AxonValue.array(route.get().shards().stream().map(AxonValue::str).toList()));
        return Plan.local(AxonValue.object(out));
    }

    /** Vazio quando a consulta é do shard base. */
    private Optional<Route> route(ShardMap map, GatewaySession session, Statement.Select select) {
        String table = select.from() == null ? null : StatementScan.tableOf(select.from()).orElse(null);
        String field = table == null ? null : map.shardKey(GatewayRules.key(session, table)).orElse(null);
        if (field == null) {
            requireHomeSelect(map, session, select, table);
            return Optional.empty();
        }
        requireDistributable(select, table);
        List<AxonValue> values = null;
        if (select.from() instanceof Expr.RecordId && field.equals("id")) {
            values = List.of(context.evaluate(select.from(), session));
        } else {
            Optional<List<AxonValue>> restriction =
                ShardRouting.restriction(select.cond(), field, expr -> context.evaluate(expr, session));
            if (restriction.isPresent()) {
                values = restriction.get();
            }
        }
        if (values == null) {
            return Optional.of(new Route(table, field, map.shardIds()));
        }
        List<Integer> slots = new ArrayList<>(values.size());
        for (AxonValue value : values) {
            slots.add(GatewayRules.slot(field, table, value));
        }
        List<String> owners = GatewayRules.owners(map, slots);
        return Optional.of(new Route(table, field, owners.isEmpty() ? List.of(map.home().id()) : owners));
    }

    private static void requireDistributable(Statement.Select select, String table) {
        if (!select.joins().isEmpty()) {
            throw ShardException.refused("cross_join", table);
        }
        if (!select.fetch().isEmpty()) {
            throw ShardException.refused("cross_fetch", table);
        }
        if (select.timeTravel() != null) {
            throw ShardException.refused("cross_time_travel", table);
        }
        GatewayRules.requireSelfContained(select, table);
    }

    private void requireHomeSelect(ShardMap map, GatewaySession session, Statement.Select select, String table) {
        if (!GatewayRules.databaseSharded(map, session)) {
            return;
        }
        if (table == null && select.from() instanceof Expr.Param) {
            AxonValue value = context.evaluate(select.from(), session);
            if (value.isRecordId() && GatewayRules.sharded(map, session, value.asRecordId().table())) {
                throw ShardException.refused("target_unknown");
            }
        }
        if (!select.fetch().isEmpty()) {
            throw ShardException.refused("cross_fetch", table == null ? "-" : table);
        }
        for (Statement.Join join : select.joins()) {
            String joined = StatementScan.tableOf(join.source()).orElse(null);
            if (joined == null || GatewayRules.sharded(map, session, joined)) {
                throw ShardException.refused("cross_join", joined == null ? "-" : joined);
            }
        }
        if (select.from() instanceof Expr.SubQuery sub) {
            StatementScan scan = StatementScan.of(sub);
            if (scan.opaqueSubquery()
                || scan.subqueryTables().stream().anyMatch(t -> GatewayRules.sharded(map, session, t))) {
                throw ShardException.refused("cross_subquery", String.join(", ", scan.subqueryTables()));
            }
        }
        GatewayRules.requireHomeOnly(map, session, select);
    }

    /**
     * A consulta vai inteira a cada shard, que a executa em modo parcial; o gateway funde as partes. Durante uma
     * movimentação, a origem e o destino recebem também os slots que lhes pertencem.
     */
    private Plan scatter(ShardMap map, GatewaySession session, ScriptStatements.Piece piece,
                         Statement.Select select, Route route) {
        List<Plan.Part> parts = new ArrayList<>(route.shards().size());
        for (String shard : route.shards()) {
            boolean moving = map.migration()
                .map(m -> m.source().equals(shard) || m.target().equals(shard)).orElse(false);
            SlotFilter filter = moving ? new SlotFilter(route.table(), route.field(), map.slotsOf(shard)) : null;
            parts.add(new Plan.Part(shard, piece.text(), Map.of(), true, filter));
        }
        return new Plan(parts,
            replies -> ShardPartial.merge(context.scratch, select, context.scope(session), replies), false);
    }
}
