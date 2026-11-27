package com.axonbase.core.engine;

import com.axonbase.common.Messages;
import com.axonbase.core.Session;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * As duas metades de uma consulta espalhada. Cada shard devolve uma parte (linhas com as chaves de ordenação, ou
 * grupos com os acumuladores), e {@link #merge} junta as partes dando o resultado que um nó único daria.
 */
public final class ShardPartial {

    private static final String ROWS = "rows";
    private static final String GROUPS = "groups";

    private ShardPartial() {
    }

    static AxonValue groups(GroupAggregation aggregation) {
        return envelope(GROUPS, aggregation.exportPartial());
    }

    /** Uma linha já projetada, com a chave do registro e os valores do ORDER BY. */
    static AxonValue row(String key, AxonValue[] order, AxonValue row) {
        Map<String, AxonValue> out = new LinkedHashMap<>();
        out.put("k", AxonValue.str(key));
        out.put("o", AxonValue.array(List.of(order)));
        out.put("r", row);
        return AxonValue.object(out);
    }

    static AxonValue rows(List<AxonValue> rows) {
        return envelope(ROWS, AxonValue.array(rows));
    }

    static String maskedAggregation() {
        return "en".equals(Messages.language())
            ? "aggregation across shards is not supported while data masking applies to this user"
            : "agregação entre shards não é suportada com mascaramento de dados para este usuário";
    }

    static String maskedOrder() {
        return "en".equals(Messages.language())
            ? "this ORDER BY across shards would expose data masked for this user: order only by columns that are "
                + "not masked"
            : "este ORDER BY entre shards exporia dados mascarados para este usuário: ordene só por colunas sem "
                + "máscara";
    }

    private static AxonValue envelope(String kind, AxonValue items) {
        Map<String, AxonValue> out = new LinkedHashMap<>();
        out.put("kind", AxonValue.str(kind));
        out.put("items", items);
        return AxonValue.object(out);
    }

    /**
     * Junta as partes de um SELECT.
     *
     * @param scratch  datastore usado só para avaliar expressões (HAVING, colunas dos grupos, START e LIMIT)
     * @param scope    sessão com as variáveis da consulta
     * @param partials as respostas dos shards, na ordem do mapa
     */
    public static AxonValue merge(Datastore scratch, Statement.Select select, Session scope,
                                  List<AxonValue> partials) {
        Executor executor = new Executor(scratch);
        int start = select.start() == null ? 0 : (int) executor.evaluate(select.start(), scope).asLong();
        Long limit = select.limit() == null ? null : executor.evaluate(select.limit(), scope).asLong();
        boolean aggregated = partials.stream().anyMatch(p -> p.asObject().get("kind").asString().equals(GROUPS));
        List<AxonValue> all;
        if (aggregated) {
            GroupAggregation aggregation = executor.partialAggregation(select, scope);
            for (AxonValue partial : partials) {
                aggregation.absorbPartial(partial.asObject().get("items"));
            }
            aggregation.orderByOrigin();
            all = aggregation.finish();
        } else {
            all = interleave(select, partials);
        }
        int from = Math.min(Math.max(start, 0), all.size());
        int to = limit == null ? all.size() : (int) Math.min(all.size(), from + Math.max(limit, 0));
        List<AxonValue> out = all.subList(from, to);
        // as mesmas formas de um nó único: agregado escalar, ONLY e o record id devolvem o valor sem a lista
        boolean single = out.size() == 1 && (aggregated && select.valueOnly() && !select.grouped() || select.only()
            || select.from() instanceof com.axonbase.parser.ast.Expr.RecordId);
        return single ? out.get(0) : AxonValue.array(out);
    }

    private record Entry(String key, List<AxonValue> order, AxonValue row) {
    }

    /** Intercala as linhas pelos valores do ORDER BY, desempatando pela chave, que é a ordem de um nó único. */
    private static List<AxonValue> interleave(Statement.Select select, List<AxonValue> partials) {
        List<Entry> entries = new ArrayList<>();
        for (AxonValue partial : partials) {
            for (AxonValue item : partial.asObject().get("items").asArray()) {
                Map<String, AxonValue> row = item.asObject();
                entries.add(new Entry(row.get("k").asString(), row.get("o").asArray(), row.get("r")));
            }
        }
        List<Statement.OrderTerm> orders = select.orders();
        entries.sort((a, b) -> {
            for (int i = 0; i < a.order().size(); i++) {
                int c = a.order().get(i).compareTo(b.order().get(i));
                if (c != 0) {
                    return orders.get(i).descending() ? -c : c;
                }
            }
            return a.key().compareTo(b.key());
        });
        List<AxonValue> rows = new ArrayList<>(entries.size());
        entries.forEach(entry -> rows.add(entry.row()));
        return rows;
    }
}
