package com.axonbase.core.shard;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Function;

/**
 * O gateway sem estado: recebe AxonQL, decide os shards de cada instrução, envia e junta as respostas. Não guarda dado
 * algum além de uma cópia com validade do mapa dos shards, então qualquer instância atende qualquer cliente.
 */
public final class ShardGateway implements AutoCloseable {

    /**
     * @param mapTtl           validade da cópia do mapa; a movimentação de slots conta com ela
     * @param checkExplicitIds aceita record id escolhido em tabela dividida por outro campo, consultando os demais
     *                         shards antes (sem atomicidade)
     */
    public record Options(Duration mapTtl, boolean checkExplicitIds) {

        public static Options defaults() {
            return new Options(Duration.ofSeconds(2), false);
        }

        public Options withMapTtl(Duration ttl) {
            return new Options(ttl, checkExplicitIds);
        }

        public Options withCheckExplicitIds(boolean check) {
            return new Options(mapTtl, check);
        }
    }

    private final GatewayContext context;
    private final ShardMapCache maps;
    private final CreatePlanner creates;
    private final MutationPlanner mutations;
    private final ReadPlanner reads;
    private final DdlPlanner definitions;
    private final SagaPlanner sagas;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * @param store   o plano de controle
     * @param clients cria o cliente de um shard a partir do que o mapa diz dele
     */
    public ShardGateway(ShardMapStore store, Function<ShardInfo, ShardClient> clients, Options options,
                        HybridClock clock) {
        this.context = new GatewayContext(clients, clock, options);
        this.maps = new ShardMapCache(store::require, options.mapTtl(), System::nanoTime);
        this.creates = new CreatePlanner(context);
        this.mutations = new MutationPlanner(context, creates);
        this.reads = new ReadPlanner(context);
        this.definitions = new DdlPlanner(context, store, maps);
        this.sagas = new SagaPlanner(context);
    }

    /** O mapa que o gateway está usando (renovado quando vencido). */
    public ShardMap map() {
        return maps.get();
    }

    /** Joga fora a cópia do mapa e lê de novo. */
    public ShardMap refresh() {
        return maps.reload();
    }

    /** O relógio do gateway: o maior carimbo já visto, que o cliente pode devolver para preservar a ordem. */
    public HlcTimestamp clock() {
        return context.clock.last();
    }

    /** Adota o carimbo que um cliente trouxe de outra resposta. */
    public void observe(HlcTimestamp seen) {
        context.observe(seen);
    }

    /**
     * Executa um script. O resultado é o da última instrução, como num nó único.
     *
     * @param vars variáveis do script, somadas às da sessão
     */
    public AxonValue execute(GatewaySession session, String script, Map<String, AxonValue> vars) {
        if (vars != null) {
            session.vars().putAll(vars);
        }
        List<ScriptStatements.Piece> pieces = ScriptStatements.split(script);
        AxonValue result = AxonValue.nul();
        for (int i = 0; i < pieces.size(); i++) {
            if (pieces.get(i).statement() instanceof Statement.Begin) {
                int end = blockEnd(pieces, i);
                result = transaction(session, pieces.subList(i + 1, end), pieces.get(end).statement());
                i = end;
                continue;
            }
            result = statement(session, pieces.get(i));
        }
        return result;
    }

    private AxonValue statement(GatewaySession session, ScriptStatements.Piece piece) {
        ShardMap map = maps.get();
        Statement statement = piece.statement();
        return switch (statement) {
            case Statement.Use use -> use(session, use);
            case Statement.Let let -> let(map, session, piece, let);
            case Statement.BeginSaga begin -> sagas.begin(map, session, begin);
            case Statement.JoinSaga join -> sagas.join(map, session, join);
            case Statement.LeaveSaga leave -> sagas.leave(session);
            case Statement.CommitSaga commit ->
                sagas.finish(map, session, commit.name(), commit.correlationId(), false);
            case Statement.CancelSaga cancel ->
                sagas.finish(map, session, cancel.name(), cancel.correlationId(), true);
            case Statement.ShowSagaTransaction show -> sagas.show(map, session, piece.text());
            case Statement.Commit commit -> throw ShardException.refused("txn_unbalanced");
            case Statement.Cancel cancel -> throw ShardException.refused("txn_unbalanced");
            default -> DdlPlanner.isDefinition(statement)
                ? definitions.run(map, session, piece)
                : run(map, session, plan(map, session, piece));
        };
    }

    private Plan plan(ShardMap map, GatewaySession session, ScriptStatements.Piece piece) {
        Statement statement = piece.statement();
        return switch (statement) {
            case Statement.Select select -> reads.select(map, session, piece, select);
            case Statement.Explain explain -> reads.explain(map, session, piece, explain);
            case Statement.Create create -> creates.create(map, session, piece, create);
            case Statement.Insert insert -> creates.insert(map, session, piece, insert);
            case Statement.Update update -> mutations.update(map, session, piece, update);
            case Statement.Delete delete -> mutations.delete(map, session, piece, delete);
            case Statement.Relate relate ->
                mutations.relate(map, session, piece, relate.from(), relate.kind(), relate.to());
            case Statement.Unrelate unrelate ->
                mutations.relate(map, session, piece, unrelate.from(), unrelate.kind(), unrelate.to());
            case Statement.Live live -> throw ShardException.refused("cross_live");
            case Statement.Kill kill -> throw ShardException.refused("cross_live");
            case Statement.Partition partition -> {
                // partição e TTL só no shard base deixariam os outros sem a regra, sem aviso
                String table = partition.command().table();
                if (GatewayRules.databaseSharded(map, session) && GatewayRules.sharded(map, session, table)) {
                    throw ShardException.refused("sharded_partition", table);
                }
                yield partition.command().readOnly()
                    ? Plan.read(map.home().id(), piece.text()) : Plan.write(map.home().id(), piece.text());
            }
            default -> {
                // o resto (INFO, SHOW, DESCRIBE, CALL, IF...) é do shard base
                GatewayRules.requireHomeOnly(map, session, statement);
                yield Datastore.isReadOnly(statement)
                    ? Plan.read(map.home().id(), piece.text()) : Plan.write(map.home().id(), piece.text());
            }
        };
    }

    private AxonValue run(ShardMap map, GatewaySession session, Plan plan) {
        List<Plan.Part> parts = plan.parts();
        boolean inSaga = plan.write() && session.saga() != null;
        if (parts.isEmpty()) {
            return plan.merge().apply(List.of());
        }
        if (inSaga) {
            List<Plan.Part> joined = new ArrayList<>(parts.size());
            for (Plan.Part part : parts) {
                sagas.open(map, session, part.shard());
                joined.add(new Plan.Part(part.shard(), SagaPlanner.joinPrefix(session.saga()) + part.text(),
                    part.vars(), part.partial(), part.filter()));
            }
            parts = joined;
        }
        if (parts.size() == 1) {
            return plan.merge().apply(List.of(context.send(map, parts.get(0), session, false)));
        }
        return plan.merge().apply(plan.write() ? sequentially(map, session, parts) : together(map, session, parts));
    }

    /** Escritas: um shard de cada vez, para saber exatamente quem gravou quando algo falha. */
    private List<AxonValue> sequentially(ShardMap map, GatewaySession session, List<Plan.Part> parts) {
        List<AxonValue> replies = new ArrayList<>(parts.size());
        List<String> done = new ArrayList<>();
        for (Plan.Part part : parts) {
            try {
                if (!done.isEmpty() && maps.reload().epoch() != map.epoch()) {
                    // o plano vale para a época em que foi feito: o que falta poderia cair no shard antigo
                    throw new ShardException(ShardException.Code.STALE_MAP, ShardMessages.get("map_changed"));
                }
                replies.add(context.send(map, part, session, false));
                done.add(part.shard());
            } catch (RuntimeException e) {
                if (done.isEmpty()) {
                    throw e;
                }
                throw new ShardException(ShardException.Code.PARTIAL_WRITE,
                    ShardMessages.get("partial_write", done, part.shard(), e.getMessage()), e);
            }
        }
        return replies;
    }

    /** Leituras: todos os shards ao mesmo tempo. Uma falha derruba a consulta: nunca há resultado parcial. */
    private List<AxonValue> together(ShardMap map, GatewaySession session, List<Plan.Part> parts) {
        List<Future<AxonValue>> pending = new ArrayList<>(parts.size());
        for (Plan.Part part : parts) {
            pending.add(workers.submit(() -> context.send(map, part, session, false)));
        }
        List<AxonValue> replies = new ArrayList<>(parts.size());
        try {
            for (Future<AxonValue> reply : pending) {
                replies.add(reply.get());
            }
        } catch (ExecutionException e) {
            pending.forEach(f -> f.cancel(true));
            if (e.getCause() instanceof RuntimeException failure) {
                throw failure;
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            pending.forEach(f -> f.cancel(true));
            Thread.currentThread().interrupt();
            throw new ShardException(ShardException.Code.UNAVAILABLE,
                ShardMessages.get("shard_unavailable", "*", "interrompido"), e);
        }
        return replies;
    }

    private static int blockEnd(List<ScriptStatements.Piece> pieces, int begin) {
        for (int i = begin + 1; i < pieces.size(); i++) {
            Statement statement = pieces.get(i).statement();
            if (statement instanceof Statement.Commit || statement instanceof Statement.Cancel) {
                return i;
            }
        }
        throw ShardException.refused("txn_unbalanced");
    }

    /** Um bloco BEGIN ... COMMIT vale quando tudo cai no mesmo shard: vai inteiro para ele, numa transação. */
    private AxonValue transaction(GatewaySession session, List<ScriptStatements.Piece> body, Statement end) {
        if (end instanceof Statement.Cancel) {
            return AxonValue.nul();
        }
        if (session.saga() != null) {
            throw ShardException.refused("txn_in_saga");
        }
        if (body.isEmpty()) {
            return AxonValue.nul();
        }
        ShardMap map = maps.get();
        String shard = null;
        StringBuilder script = new StringBuilder();
        Map<String, AxonValue> vars = new java.util.LinkedHashMap<>();
        for (int i = 0; i < body.size(); i++) {
            ScriptStatements.Piece piece = body.get(i);
            Statement statement = piece.statement();
            if (statement instanceof Statement.Savepoint || statement instanceof Statement.Release
                || statement instanceof Statement.RollbackTo) {
                script.append(piece.text()).append(";\n");
                continue;
            }
            if (statement instanceof Statement.Let) {
                // o valor decidiria o shard das instruções seguintes, e o gateway não o conhece
                throw ShardException.refused("txn_let");
            }
            if (statement instanceof Statement.Use || statement instanceof Statement.Begin
                || DdlPlanner.isDefinition(statement)) {
                throw ShardException.refused("txn_statement");
            }
            Plan plan = plan(map, session, piece);
            if (plan.parts().size() != 1 || plan.parts().get(0).partial()
                || shard != null && !shard.equals(plan.parts().get(0).shard())) {
                throw ShardException.refused("txn_multi_shard");
            }
            Plan.Part part = plan.parts().get(0);
            shard = part.shard();
            String text = part.text();
            // cada INSERT reescrito usa a própria variável
            for (Map.Entry<String, AxonValue> own : part.vars().entrySet()) {
                String unique = own.getKey() + i;
                text = text.replace("$" + own.getKey(), "$" + unique);
                vars.put(unique, own.getValue());
            }
            script.append(text).append(";\n");
        }
        if (shard == null) {
            shard = map.home().id();
        }
        context.send(map, Plan.Part.of(shard, script.toString(), vars), session, true);
        return AxonValue.nul();
    }

    private AxonValue use(GatewaySession session, Statement.Use use) {
        session.use(name(session, use.ns()), name(session, use.db()));
        return AxonValue.nul();
    }

    private String name(GatewaySession session, Expr expr) {
        if (expr == null) {
            return null;
        }
        if (expr instanceof Expr.Ident ident) {
            return ident.name();
        }
        AxonValue value = context.evaluate(expr, session);
        return value.isString() ? value.asString() : value.toString();
    }

    private AxonValue let(ShardMap map, GatewaySession session, ScriptStatements.Piece piece, Statement.Let let) {
        AxonValue value;
        if (GatewayExpressions.local(let.value(), session.vars().keySet())) {
            value = context.evaluate(let.value(), session);
        } else {
            // o resto é calculado por um shard, com a autorização do cliente
            GatewayRules.requireHomeOnly(map, session, let);
            value = context.send(map, Plan.Part.of(map.home().id(), piece.text() + ";\nRETURN $" + let.name()),
                session, false);
        }
        session.vars().put(let.name(), value);
        return AxonValue.nul();
    }

    @Override
    public void close() {
        workers.shutdownNow();
    }
}
