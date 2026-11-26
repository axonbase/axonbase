package com.axonbase.core.shard;

import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** {@code CREATE} e {@code INSERT}: cada registro vai ao shard dono da chave dele. */
final class CreatePlanner {

    private static final String ROWS = "__axon_rows";

    private final GatewayContext context;

    CreatePlanner(GatewayContext context) {
        this.context = context;
    }

    Plan create(ShardMap map, GatewaySession session, ScriptStatements.Piece piece, Statement.Create create) {
        String table = StatementScan.tableOf(create.target()).orElse(null);
        String field = table == null ? null : map.shardKey(GatewayRules.key(session, table)).orElse(null);
        if (field == null) {
            GatewayRules.requireNamedSource(map, context, session, create.target());
            GatewayRules.requireHomeOnly(map, session, create);
            return Plan.write(map.home().id(), piece.text());
        }
        GatewayRules.requireSelfContained(create, table);
        AxonValue explicit = create.target() instanceof Expr.RecordId id ? recordKey(session, id.key()) : null;
        AxonValue chosen = explicit != null ? explicit : dataField(session, create.data(), "id");
        String text = piece.text();
        AxonValue key = chosen;
        if (key == null) {
            String generated = newKey();
            key = AxonValue.str(generated);
            text = withKey(piece, create, table, generated);
        }
        AxonValue value = field.equals("id") ? engineKey(key) : dataField(session, create.data(), field);
        if (value == null) {
            throw ShardException.refused("shard_key_required", table, field);
        }
        GatewayRules.requireStorableKey(value);
        int slot = SlotHash.ofValue(value);
        GatewayRules.requireSettled(map, List.of(slot));
        if (!field.equals("id") && chosen != null) {
            requireUnusedId(map, session, table, chosen, map.ownerOf(slot));
        }
        return Plan.write(map.ownerOf(slot), text);
    }

    Plan insert(ShardMap map, GatewaySession session, ScriptStatements.Piece piece, Statement.Insert insert) {
        String table = insert.table();
        String field = map.shardKey(GatewayRules.key(session, table)).orElse(null);
        if (field == null) {
            if (insert.relation() && GatewayRules.databaseSharded(map, session)) {
                throw ShardException.refused("cross_relation", table);
            }
            GatewayRules.requireHomeOnly(map, session, insert);
            return Plan.write(map.home().id(), piece.text());
        }
        if (insert.relation()) {
            throw ShardException.refused("cross_relation", table);
        }
        GatewayRules.requireSelfContained(insert, table);
        if (session.saga() != null) {
            throw ShardException.refused("saga_insert");
        }
        boolean silent = insert.ret() != null && insert.ret().kind() == Statement.ReturnKind.NONE;
        if (insert.ret() != null && !silent && insert.ret().kind() != Statement.ReturnKind.AFTER) {
            throw ShardException.refused("insert_return");
        }
        List<AxonValue> rows = rows(context.evaluate(insert.data(), session));
        // por shard: as linhas dele e a posição de cada uma no pedido original
        Map<String, List<AxonValue>> byShard = new LinkedHashMap<>();
        Map<String, List<Integer>> positions = new LinkedHashMap<>();
        List<Integer> slots = new ArrayList<>(rows.size());
        for (int i = 0; i < rows.size(); i++) {
            Map<String, AxonValue> row = new LinkedHashMap<>(rows.get(i).asObject());
            AxonValue id = row.get("id");
            int slot;
            if (field.equals("id")) {
                if (id == null) {
                    id = AxonValue.str(newKey());
                    row.put("id", id);
                }
                slot = SlotHash.ofValue(engineKey(id));
            } else {
                AxonValue value = row.get(field);
                if (value == null) {
                    throw ShardException.refused("shard_key_required", table, field);
                }
                GatewayRules.requireStorableKey(value);
                slot = SlotHash.ofValue(value);
                if (id != null) {
                    requireUnusedId(map, session, table, id, map.ownerOf(slot));
                } else {
                    row.put("id", AxonValue.str(newKey()));
                }
            }
            slots.add(slot);
            String shard = map.ownerOf(slot);
            byShard.computeIfAbsent(shard, s -> new ArrayList<>()).add(AxonValue.object(row));
            positions.computeIfAbsent(shard, s -> new ArrayList<>()).add(i);
        }
        GatewayRules.requireSettled(map, slots);
        String text = "INSERT " + (insert.ignore() ? "IGNORE " : "") + "INTO " + table + " $" + ROWS
            + (silent ? " RETURN NONE" : "");
        List<Plan.Part> parts = new ArrayList<>();
        List<List<Integer>> order = new ArrayList<>();
        byShard.forEach((shard, items) -> {
            parts.add(Plan.Part.of(shard, text, Map.of(ROWS, AxonValue.array(items))));
            order.add(positions.get(shard));
        });
        if (parts.isEmpty()) {
            return Plan.local(silent ? AxonValue.nul() : AxonValue.array(List.of()));
        }
        int total = rows.size();
        return new Plan(parts, replies -> silent ? replies.get(0) : inRequestOrder(replies, order, total), true);
    }

    private static AxonValue inRequestOrder(List<AxonValue> replies, List<List<Integer>> order, int total) {
        AxonValue[] out = new AxonValue[total];
        List<AxonValue> unplaced = new ArrayList<>();
        for (int part = 0; part < replies.size(); part++) {
            List<AxonValue> records = replies.get(part).isArray() ? replies.get(part).asArray() : List.of();
            if (records.size() == order.get(part).size()) {
                for (int i = 0; i < records.size(); i++) {
                    out[order.get(part).get(i)] = records.get(i);
                }
            } else {
                unplaced.addAll(records);
            }
        }
        List<AxonValue> result = new ArrayList<>(total);
        for (AxonValue record : out) {
            if (record != null) {
                result.add(record);
            }
        }
        result.addAll(unplaced);
        return AxonValue.array(result);
    }

    /** As linhas de um INSERT, como o motor as lê: um objeto, ou os objetos de uma lista. */
    private static List<AxonValue> rows(AxonValue data) {
        if (data.isObject()) {
            return List.of(data);
        }
        if (data.isArray()) {
            return data.asArray().stream().filter(AxonValue::isObject).toList();
        }
        return List.of();
    }

    private AxonValue recordKey(GatewaySession session, Expr key) {
        return engineKey(context.evaluate(key, session));
    }

    /** O valor que os dados gravam num campo, quando está escrito neles; {@code null} se o campo não aparece. */
    AxonValue dataField(GatewaySession session, Statement.Data data, String name) {
        if (data instanceof Statement.Data.SetClause set) {
            Expr found = null;
            for (Statement.Assignment assignment : set.assignments()) {
                if (GatewayRules.assignedField(assignment).equals(name)) {
                    found = assignment.value();
                }
            }
            return found == null ? null : constant(session, found, name);
        }
        Expr content = data instanceof Statement.Data.Content c ? c.content()
            : data instanceof Statement.Data.Replace r ? r.replace()
            : data instanceof Statement.Data.Merge m ? m.merge() : null;
        if (content == null) {
            return null;
        }
        if (content instanceof Expr.ObjectLit object) {
            Expr found = null;
            for (Expr.ObjectLit.Entry entry : object.entries()) {
                if (entry.key().equals(name)) {
                    found = entry.value();
                }
            }
            return found == null ? null : constant(session, found, name);
        }
        AxonValue value = constant(session, content, name);
        return value.isObject() ? value.asObject().get(name) : null;
    }

    private AxonValue constant(GatewaySession session, Expr expr, String name) {
        if (!ShardRouting.isConstant(expr)) {
            throw ShardException.refused("shard_key_not_constant", name);
        }
        return context.evaluate(expr, session);
    }

    /**
     * Numa tabela dividida por um campo, dois shards poderiam guardar o mesmo record id. Por padrão o id escolhido é
     * recusado; com a checagem ligada, todos os outros shards são consultados antes (sem atomicidade).
     */
    void requireUnusedId(ShardMap map, GatewaySession session, String table, AxonValue id, String owner) {
        if (!context.options.checkExplicitIds()) {
            throw ShardException.refused("explicit_id", table);
        }
        AxonValue key = engineKey(id);
        String query = "SELECT * FROM " + table + ":" + GatewayRules.keyLiteral(key);
        for (String shard : map.shardIds()) {
            if (shard.equals(owner)) {
                continue;
            }
            AxonValue found = context.send(map, Plan.Part.of(shard, query), session, false);
            if (found.isObject() || found.isArray() && !found.asArray().isEmpty()) {
                throw ShardException.refused("id_exists", table + ":" + GatewayRules.keyLiteral(key), shard);
            }
        }
    }

    /**
     * Chave gerada pelo gateway: aleatória, começa por letra e não tem hífen, para poder ser escrita sem aspas num
     * record id (a compensação da saga nativa escreve assim).
     */
    private static String newKey() {
        return "g" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * A chave que o motor grava para um id escrito nos dados ou no alvo: a chave de um record id, um número inteiro, ou
     * o texto como está (um texto {@code tabela:x} vira a chave inteira, como no nó único).
     */
    private static AxonValue engineKey(AxonValue id) {
        if (id.isRecordId()) {
            Object key = id.asRecordId().key();
            if (key instanceof AxonValue value) {
                return value;
            }
            return key instanceof Long number ? AxonValue.num(number) : AxonValue.str(String.valueOf(key));
        }
        return id.isNumber() ? AxonValue.num(id.asLong()) : id;
    }

    /** Escreve a chave gerada no texto, logo depois do nome da tabela, e confere o resultado. */
    private static String withKey(ScriptStatements.Piece piece, Statement.Create create, String table, String key) {
        // depois de "create" (e de "only") vem o nome da tabela
        int end = ScriptStatements.units(piece.text()).get(create.only() ? 2 : 1).end();
        String text = piece.text().substring(0, end) + ":\"" + key + "\"" + piece.text().substring(end);
        Statement expected = new Statement.Create(create.only(),
            new Expr.RecordId(new Expr.Ident(table), new Expr.Literal(AxonValue.str(key))), create.data(), create.ret());
        List<Statement> parsed = AxonQl.parse(text).statements();
        if (parsed.size() != 1 || !parsed.get(0).equals(expected)) {
            throw ShardException.refused("create_id_unsupported", table);
        }
        return text;
    }
}
