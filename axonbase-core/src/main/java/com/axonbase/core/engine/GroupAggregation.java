package com.axonbase.core.engine;

import com.axonbase.core.catalog.Document;
import com.axonbase.core.catalog.RecordId;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Agregação de um SELECT com {@code count/sum/avg/min/max}, {@code GROUP BY}, {@code GROUP ALL}, {@code HAVING} e
 * {@code ORDER BY} sobre o resultado agrupado.
 *
 * <p>Os documentos entram um a um em {@link #accept(Document)} e cada grupo guarda só os acumuladores e o primeiro
 * documento (para os campos que não são agregados), então a memória cresce com o número de grupos, não de linhas.
 * Depois de {@link #finish()} as linhas saem filtradas por HAVING e ordenadas; START e LIMIT são do chamador.</p>
 *
 * <p>Cada chamada de agregado dentro de uma expressão (inclusive {@code sum(x) / count()}) é trocada por um
 * identificador interno cujo valor é calculado por grupo; a expressão reescrita é avaliada pelo mesmo avaliador
 * das linhas comuns, então operadores e funções funcionam igual.</p>
 *
 * <p>As reduções {@code math::*} de um argumento também agregam aqui; fora de uma consulta agrupada continuam sendo
 * funções de linha sobre listas.</p>
 */
final class GroupAggregation {

    /** Avaliação de expressões em uma linha, fornecida pelo executor. */
    interface RowEval {
        AxonValue eval(Expr expr, Document row);

        boolean truthy(AxonValue value);
    }

    /** Escala do {@code avg}, a mesma de {@code math::mean} e da divisão. */
    private static final int AVG_SCALE = 10;
    private static final String SLOT = "\u0001agg";
    private static final String ROW_TABLE = "\u0001group";
    /** Reduzem uma lista numa consulta comum e o grupo inteiro numa consulta agrupada. */
    private static final Set<String> MATH_REDUCTIONS =
        Set.of("math::sum", "math::min", "math::max", "math::mean", "math::median", "math::product");

    private record Spec(String name, Expr arg) {
    }

    private record Column(String name, Expr expr) {
    }

    private static final class Group {
        Document representative;
        /** Chave do primeiro registro, quando o grupo veio de outro shard. */
        String origin;
        final State[] states;

        Group(Document representative, List<Spec> specs) {
            this.representative = representative;
            this.states = new State[specs.size()];
            for (int i = 0; i < states.length; i++) {
                states[i] = State.of(specs.get(i).name());
            }
        }
    }

    private record Out(AxonValue row, List<AxonValue> sortKeys) {
    }

    private final Statement.Select sel;
    private final RowEval rowEval;
    private final List<Spec> specs = new ArrayList<>();
    private final List<Column> columns = new ArrayList<>();
    private final List<Expr> groupExprs = new ArrayList<>();
    private final List<Integer> hiddenGroups = new ArrayList<>();
    private final Expr having;
    private final List<Statement.OrderTerm> orders = new ArrayList<>();
    private final Map<String, Group> groups = new LinkedHashMap<>();

    GroupAggregation(Statement.Select sel, RowEval rowEval, Function<Expr, String> namer) {
        this.sel = sel;
        this.rowEval = rowEval;
        Map<String, Expr> aliases = new HashMap<>();
        for (Expr f : sel.fields()) {
            if (f instanceof Expr.Alias a && !containsAggregate(a.expr())) {
                aliases.put(a.name(), a.expr());
            }
        }
        for (Expr g : sel.group()) {
            // GROUP BY trecho: se "trecho" é o apelido de um campo do SELECT, agrupa pela expressão dele
            groupExprs.add(g instanceof Expr.Ident id && aliases.containsKey(id.name()) ? aliases.get(id.name()) : g);
        }
        for (Expr f : sel.fields()) {
            if (f instanceof Expr.Ident id && id.name().equals("*")) {
                continue;
            }
            columns.add(new Column(namer.apply(f), extract(f)));
        }
        // um grupo que não aparece no SELECT continua saindo como group.N, como antes
        for (int i = 0; i < groupExprs.size(); i++) {
            Expr g = groupExprs.get(i);
            boolean selected = sel.fields().stream().anyMatch(f -> unwrap(f).equals(g));
            if (!selected) {
                hiddenGroups.add(i);
            }
        }
        this.having = sel.having() == null ? null : extract(sel.having());
        for (Statement.OrderTerm o : sel.orders()) {
            orders.add(new Statement.OrderTerm(extract(o.field()), o.descending()));
        }
    }

    /** Há agregado em algum lugar da expressão (sem olhar dentro de subqueries, que agregam por conta própria). */
    static boolean containsAggregate(Expr e) {
        return switch (e) {
            case Expr.Call c -> isAggregateName(c.name()) || c.args().stream().anyMatch(GroupAggregation::containsAggregate);
            case Expr.Alias a -> containsAggregate(a.expr());
            case Expr.Binary b -> containsAggregate(b.left()) || containsAggregate(b.right());
            case Expr.Unary u -> containsAggregate(u.operand());
            case Expr.Cast c -> containsAggregate(c.operand());
            case Expr.ObjectLit o -> o.entries().stream().anyMatch(en -> containsAggregate(en.value()));
            case Expr.ArrayLit a -> a.items().stream().anyMatch(GroupAggregation::containsAggregate);
            default -> false;
        };
    }

    private static boolean isAggregateName(String name) {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "count", "sum", "avg", "min", "max", Expr.COUNT_DISTINCT -> true;
            default -> false;
        };
    }

    /** {@code math::sum(x)} e afins, desde que não envolvam outro agregado nem sejam a própria expressão do grupo. */
    private boolean isMathReduction(Expr.Call c) {
        return c.args().size() == 1 && MATH_REDUCTIONS.contains(c.name().toLowerCase(Locale.ROOT))
            && !containsAggregate(c.args().get(0)) && !groupExprs.contains(c);
    }

    private static Expr unwrap(Expr f) {
        return f instanceof Expr.Alias a ? a.expr() : f;
    }

    private Expr extract(Expr e) {
        return switch (e) {
            case Expr.Call c when isAggregateName(c.name()) || isMathReduction(c) -> {
                String slot = SLOT + specs.size();
                specs.add(new Spec(c.name().toLowerCase(Locale.ROOT), c.args().isEmpty() ? null : c.args().get(0)));
                yield new Expr.Ident(slot);
            }
            case Expr.Call c -> new Expr.Call(c.name(), c.args().stream().map(this::extract).toList());
            case Expr.Alias a -> new Expr.Alias(extract(a.expr()), a.name());
            case Expr.Binary b -> new Expr.Binary(b.op(), extract(b.left()), extract(b.right()));
            case Expr.Unary u -> new Expr.Unary(u.op(), extract(u.operand()));
            case Expr.Cast c -> new Expr.Cast(c.type(), extract(c.operand()));
            case Expr.ObjectLit o -> new Expr.ObjectLit(o.entries().stream()
                .map(en -> new Expr.ObjectLit.Entry(en.key(), extract(en.value()))).toList());
            case Expr.ArrayLit a -> new Expr.ArrayLit(a.items().stream().map(this::extract).toList());
            default -> e;
        };
    }

    /** Soma o documento ao grupo dele. */
    void accept(Document doc) {
        String key = groupKey(doc);
        Group group = groups.get(key);
        if (group == null) {
            group = new Group(doc, specs);
            groups.put(key, group);
        }
        for (int i = 0; i < specs.size(); i++) {
            Expr arg = specs.get(i).arg();
            group.states[i].add(arg == null ? AxonValue.num(1) : rowEval.eval(arg, doc));
        }
    }

    /**
     * Funde os grupos de outra agregação (da mesma consulta) nesta, como se os documentos dela tivessem chegado
     * depois dos desta. Grupos novos entram no fim, então juntando os lotes na ordem de leitura a ordem de chegada
     * dos grupos é a de uma varredura sequencial.
     */
    void merge(GroupAggregation other) {
        for (Map.Entry<String, Group> e : other.groups.entrySet()) {
            Group mine = groups.get(e.getKey());
            if (mine == null) {
                groups.put(e.getKey(), e.getValue());
                continue;
            }
            for (int i = 0; i < mine.states.length; i++) {
                mine.states[i].merge(e.getValue().states[i]);
            }
        }
    }

    /** Os grupos com o primeiro documento e os acumuladores, para o gateway fundir (consulta entre shards). */
    AxonValue exportPartial() {
        List<AxonValue> out = new ArrayList<>(groups.size());
        for (Map.Entry<String, Group> e : groups.entrySet()) {
            Group group = e.getValue();
            Map<String, AxonValue> item = new LinkedHashMap<>();
            item.put("key", AxonValue.str(e.getKey()));
            item.put("at", AxonValue.str(group.representative.id().key().keyString()));
            item.put("table", AxonValue.str(group.representative.id().table()));
            item.put("row", group.representative.current());
            item.put("states", AxonValue.array(java.util.Arrays.stream(group.states).map(State::export).toList()));
            out.add(AxonValue.object(item));
        }
        return AxonValue.array(out);
    }

    /** Funde os grupos exportados por um shard. O documento do grupo é o de menor chave entre todos os shards. */
    void absorbPartial(AxonValue exported) {
        for (AxonValue value : exported.asArray()) {
            Map<String, AxonValue> item = value.asObject();
            String key = item.get("key").asString();
            String at = item.get("at").asString();
            Document representative = new Document(
                new RecordId(item.get("table").asString(), AxonValue.str(at)), item.get("row"));
            Group group = groups.get(key);
            if (group == null) {
                group = new Group(representative, specs);
                group.origin = at;
                groups.put(key, group);
            } else if (at.compareTo(group.origin) < 0) {
                group.representative = representative;
                group.origin = at;
            }
            List<AxonValue> states = item.get("states").asArray();
            for (int i = 0; i < group.states.length; i++) {
                group.states[i].absorb(states.get(i));
            }
        }
    }

    /** Põe os grupos fundidos na ordem de chegada de um nó só: a da chave do primeiro registro de cada um. */
    void orderByOrigin() {
        List<Map.Entry<String, Group>> ordered = new ArrayList<>(groups.entrySet());
        ordered.sort(Comparator.comparing((Map.Entry<String, Group> e) -> e.getValue().origin));
        Map<String, Group> next = new LinkedHashMap<>();
        ordered.forEach(e -> next.put(e.getKey(), e.getValue()));
        groups.clear();
        groups.putAll(next);
    }

    private String groupKey(Document doc) {
        if (groupExprs.isEmpty()) {
            return "";
        }
        StringBuilder key = new StringBuilder();
        for (Expr g : groupExprs) {
            key.append(keyOf(rowEval.eval(g, doc))).append('\u0000');
        }
        return key.toString();
    }

    /** Linhas finais: grupos que passam no HAVING, na ordem do ORDER BY (a ordem de chegada se não houver). */
    List<AxonValue> finish() {
        if (groups.isEmpty() && (sel.groupAll() || sel.group().isEmpty())) {
            // agregado sem GROUP BY sempre devolve uma linha (count() = 0 em tabela vazia)
            groups.put("", new Group(emptyRow(), specs));
        }
        List<Out> out = new ArrayList<>();
        for (Group g : groups.values()) {
            Map<String, AxonValue> body = new LinkedHashMap<>();
            Document rep = g.representative;
            if (rep.current().isObject()) {
                body.putAll(rep.current().asObject());
            }
            for (int i = 0; i < specs.size(); i++) {
                body.put(SLOT + i, g.states[i].result());
            }
            Document ctx = row(body);
            Map<String, AxonValue> row = new LinkedHashMap<>();
            for (int i : hiddenGroups) {
                row.put("group." + i, rowEval.eval(groupExprs.get(i), rep));
            }
            for (Column c : columns) {
                row.put(c.name(), rowEval.eval(c.expr(), ctx));
            }
            Document ctxWithOutput = ctx;
            if (having != null || !orders.isEmpty()) {
                // apelidos do SELECT valem em HAVING e ORDER BY e têm prioridade sobre colunas de mesmo nome
                Map<String, AxonValue> visible = new LinkedHashMap<>(body);
                visible.putAll(row);
                ctxWithOutput = row(visible);
            }
            if (having != null && !rowEval.truthy(rowEval.eval(having, ctxWithOutput))) {
                continue;
            }
            List<AxonValue> keys = new ArrayList<>(orders.size());
            for (Statement.OrderTerm o : orders) {
                keys.add(rowEval.eval(o.field(), ctxWithOutput));
            }
            out.add(new Out(sel.valueOnly() && !columns.isEmpty() ? row.get(columns.get(0).name()) : AxonValue.object(row), keys));
        }
        if (!orders.isEmpty()) {
            out.sort(sortKeys());
        }
        List<AxonValue> rows = new ArrayList<>(out.size());
        for (Out o : out) {
            rows.add(o.row());
        }
        return rows;
    }

    private Comparator<Out> sortKeys() {
        return (a, b) -> {
            for (int i = 0; i < orders.size(); i++) {
                int c = a.sortKeys().get(i).compareTo(b.sortKeys().get(i));
                if (c != 0) {
                    return orders.get(i).descending() ? -c : c;
                }
            }
            return 0;
        };
    }

    private static Document row(Map<String, AxonValue> body) {
        return new Document(new RecordId(ROW_TABLE, AxonValue.num(0)), AxonValue.object(body));
    }

    private static Document emptyRow() {
        return row(Map.of());
    }

    /** Chave estável de um valor: 1 e 1.0 são o mesmo grupo e o mesmo valor distinto. */
    static String keyOf(AxonValue v) {
        if (v == null || v.isNull() || v.isNone()) {
            return "z";
        }
        if (v.isNumber()) {
            return "n" + v.asDecimal().stripTrailingZeros().toPlainString();
        }
        return "t" + v;
    }

    // ----------------------------------------------------------------- acumuladores

    private interface State {
        void add(AxonValue value);

        /** Soma o acumulador de outro lote (mesmo tipo) a este. */
        void merge(State other);

        /** O acumulador como valor, para atravessar a rede. */
        AxonValue export();

        /** Soma um acumulador exportado (mesmo tipo) a este. */
        void absorb(AxonValue exported);

        AxonValue result();

        static State of(String name) {
            return switch (name) {
                case "count" -> new Count();
                case Expr.COUNT_DISTINCT -> new CountDistinct();
                case "sum" -> new Sum();
                case "avg" -> new Avg();
                case "min" -> new Extreme(true);
                case "math::sum" -> new Numbers(new Sum());
                case "math::mean" -> new Numbers(new Avg());
                case "math::min" -> new Numbers(new Extreme(true));
                case "math::max" -> new Numbers(new Extreme(false));
                case "math::median" -> new Numbers(new Collected(Functions::median));
                case "math::product" -> new Numbers(new Collected(Functions::product));
                default -> new Extreme(false);
            };
        }
    }

    /** Entrada de uma redução {@code math::*}: só números contam, e um campo lista contribui com cada item. */
    private static final class Numbers implements State {
        private final State inner;

        Numbers(State inner) {
            this.inner = inner;
        }

        @Override
        public void add(AxonValue value) {
            if (value == null) {
                return;
            }
            if (value.isArray()) {
                value.asArray().forEach(this::add);
            } else if (value.isSet()) {
                value.asSet().forEach(this::add);
            } else if (value.isNumber()) {
                inner.add(value);
            }
        }

        @Override
        public void merge(State other) {
            inner.merge(((Numbers) other).inner);
        }

        @Override
        public AxonValue export() {
            return inner.export();
        }

        @Override
        public void absorb(AxonValue exported) {
            inner.absorb(exported);
        }

        @Override
        public AxonValue result() {
            return inner.result();
        }
    }

    /** Mediana e produto precisam de todos os valores do grupo, então a memória cresce com as linhas. */
    private static final class Collected implements State {
        private final List<AxonValue> values = new ArrayList<>();
        private final Function<List<AxonValue>, AxonValue> reducer;

        Collected(Function<List<AxonValue>, AxonValue> reducer) {
            this.reducer = reducer;
        }

        @Override
        public void add(AxonValue value) {
            values.add(value);
        }

        @Override
        public void merge(State other) {
            values.addAll(((Collected) other).values);
        }

        @Override
        public AxonValue export() {
            return AxonValue.array(List.copyOf(values));
        }

        @Override
        public void absorb(AxonValue exported) {
            values.addAll(exported.asArray());
        }

        @Override
        public AxonValue result() {
            return reducer.apply(values);
        }
    }

    /** {@code count()} conta todas as linhas do grupo, com ou sem argumento. */
    private static final class Count implements State {
        private long n;

        @Override
        public void add(AxonValue value) {
            n++;
        }

        @Override
        public void merge(State other) {
            n += ((Count) other).n;
        }

        @Override
        public AxonValue export() {
            return AxonValue.num(n);
        }

        @Override
        public void absorb(AxonValue exported) {
            n += exported.asLong();
        }

        @Override
        public AxonValue result() {
            return AxonValue.num(n);
        }
    }

    /** {@code count(DISTINCT x)} conta os valores diferentes e ignora nulos. */
    private static final class CountDistinct implements State {
        private final Set<String> seen = new HashSet<>();

        @Override
        public void add(AxonValue value) {
            if (value != null && !value.isNull() && !value.isNone()) {
                seen.add(keyOf(value));
            }
        }

        @Override
        public void merge(State other) {
            seen.addAll(((CountDistinct) other).seen);
        }

        @Override
        public AxonValue export() {
            return AxonValue.array(seen.stream().map(AxonValue::str).toList());
        }

        @Override
        public void absorb(AxonValue exported) {
            exported.asArray().forEach(item -> seen.add(item.asString()));
        }

        @Override
        public AxonValue result() {
            return AxonValue.num(seen.size());
        }
    }

    private static final class Sum implements State {
        private final Arith.Sum sum = new Arith.Sum();

        @Override
        public void add(AxonValue value) {
            sum.add(value);
        }

        @Override
        public void merge(State other) {
            sum.merge(((Sum) other).sum);
        }

        @Override
        public AxonValue export() {
            return sum.result();
        }

        @Override
        public void absorb(AxonValue exported) {
            sum.add(exported);
        }

        @Override
        public AxonValue result() {
            return sum.result();
        }
    }

    private static final class Avg implements State {
        private BigDecimal sum = BigDecimal.ZERO;
        private long count;

        @Override
        public void add(AxonValue value) {
            if (value != null && value.isNumber()) {
                sum = sum.add(value.asDecimal());
                count++;
            }
        }

        @Override
        public void merge(State other) {
            sum = sum.add(((Avg) other).sum);
            count += ((Avg) other).count;
        }

        @Override
        public AxonValue export() {
            Map<String, AxonValue> out = new LinkedHashMap<>();
            out.put("sum", AxonValue.num(sum));
            out.put("count", AxonValue.num(count));
            return AxonValue.object(out);
        }

        @Override
        public void absorb(AxonValue exported) {
            sum = sum.add(exported.asObject().get("sum").asDecimal());
            count += exported.asObject().get("count").asLong();
        }

        @Override
        public AxonValue result() {
            return count == 0 ? AxonValue.nul()
                : Arith.compact(sum.divide(BigDecimal.valueOf(count), AVG_SCALE, RoundingMode.HALF_UP));
        }
    }

    private static final class Extreme implements State {
        private final boolean min;
        private AxonValue best;

        Extreme(boolean min) {
            this.min = min;
        }

        @Override
        public void add(AxonValue value) {
            if (value == null || value.isNull() || value.isNone()) {
                return;
            }
            if (best == null || (min ? value.compareTo(best) < 0 : value.compareTo(best) > 0)) {
                best = value;
            }
        }

        @Override
        public void merge(State other) {
            add(((Extreme) other).best);
        }

        @Override
        public AxonValue export() {
            return AxonValue.array(best == null ? List.of() : List.of(best));
        }

        @Override
        public void absorb(AxonValue exported) {
            exported.asArray().forEach(this::add);
        }

        @Override
        public AxonValue result() {
            return best == null ? AxonValue.nul() : best;
        }
    }
}
