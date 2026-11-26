package com.axonbase.core.shard;

import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A saga nativa entre shards. Cada shard guarda o próprio ledger e compensa os próprios passos; o gateway abre a saga
 * no shard antes da primeira escrita nele, e conclui ou cancela em todos os que a têm aberta. Nada disso depende da
 * memória do gateway, então qualquer instância termina uma saga que outra começou.
 */
final class SagaPlanner {

    private static final String RUNNING = "RUNNING";

    private final GatewayContext context;

    SagaPlanner(GatewayContext context) {
        this.context = context;
    }

    AxonValue begin(ShardMap map, GatewaySession session, Statement.BeginSaga begin) {
        GatewaySession.Saga saga = new GatewaySession.Saga(begin.name(), begin.correlationId());
        session.saga(saga);
        open(map, session, map.home().id());
        // como no nó único, BEGIN não faz a sessão entrar na saga: só o JOIN (ou o próprio BEGIN aqui no gateway)
        return status(saga, RUNNING);
    }

    AxonValue join(ShardMap map, GatewaySession session, Statement.JoinSaga join) {
        GatewaySession.Saga saga = new GatewaySession.Saga(join.name(), join.correlationId());
        if (running(map, session, saga).isEmpty()) {
            throw ShardException.refused("saga_inactive", join.correlationId());
        }
        session.saga(saga);
        return AxonValue.object(Map.of("status", AxonValue.str(RUNNING)));
    }

    AxonValue leave(GatewaySession session) {
        session.saga(null);
        return AxonValue.object(Map.of("ok", AxonValue.str("1")));
    }

    AxonValue finish(ShardMap map, GatewaySession session, String name, String correlationId, boolean cancel) {
        GatewaySession.Saga saga = new GatewaySession.Saga(name, correlationId);
        List<String> open = new ArrayList<>(running(map, session, saga));
        if (open.isEmpty()) {
            throw ShardException.refused("saga_inactive", correlationId);
        }
        if (cancel) {
            java.util.Collections.reverse(open);
        }
        String text = (cancel ? "CANCEL" : "COMMIT") + " SAGA " + target(saga);
        List<String> done = new ArrayList<>();
        for (String shard : open) {
            try {
                context.send(map, Plan.Part.of(shard, text), session, false);
                done.add(shard);
            } catch (RuntimeException e) {
                throw new ShardException(ShardException.Code.PARTIAL_WRITE,
                    ShardMessages.get("partial_saga", correlationId, done, shard, e.getMessage()), e);
            }
        }
        if (session.saga() != null && session.saga().correlationId().equals(correlationId)) {
            session.saga(null);
        }
        return AxonValue.object(Map.of("status", AxonValue.str(cancel ? "FAILED" : "COMMITTED")));
    }

    /** O ledger de todos os shards: o registro da saga e os passos, cada um com o shard dele. */
    AxonValue show(ShardMap map, GatewaySession session, String text) {
        AxonValue saga = AxonValue.nul();
        List<AxonValue> steps = new ArrayList<>();
        for (String shard : map.shardIds()) {
            AxonValue reply = context.send(map, Plan.Part.of(shard, text), session, false);
            if (!reply.isObject()) {
                continue;
            }
            AxonValue found = reply.asObject().get("saga");
            if (saga.isNull() && found != null && found.isObject()) {
                saga = found;
            }
            AxonValue listed = reply.asObject().get("steps");
            if (listed != null && listed.isArray()) {
                for (AxonValue step : listed.asArray()) {
                    Map<String, AxonValue> entry = new LinkedHashMap<>(step.asObject());
                    entry.put("shard", AxonValue.str(shard));
                    steps.add(AxonValue.object(entry));
                }
            }
        }
        Map<String, AxonValue> out = new LinkedHashMap<>();
        out.put("saga", saga);
        out.put("steps", AxonValue.array(steps));
        return AxonValue.object(out);
    }

    /** Abre a saga da sessão num shard, se ainda não estiver aberta nele. */
    void open(ShardMap map, GatewaySession session, String shard) {
        GatewaySession.Saga saga = session.saga();
        if (session.sagaShards().contains(shard)) {
            return;
        }
        String state = state(map, session, shard, saga);
        if (state == null) {
            context.send(map, Plan.Part.of(shard, "BEGIN SAGA " + target(saga)), session, false);
        } else if (!state.equals(RUNNING)) {
            throw ShardException.refused("saga_inactive", saga.correlationId());
        }
        session.sagaShards().add(shard);
    }

    /** O prefixo que faz as escritas de um trecho entrarem na saga. */
    static String joinPrefix(GatewaySession.Saga saga) {
        return "JOIN SAGA " + target(saga) + ";\n";
    }

    private static String target(GatewaySession.Saga saga) {
        return saga.name() + " WITH CORRELATION " + quoted(saga.correlationId());
    }

    private static String quoted(String text) {
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private List<String> running(ShardMap map, GatewaySession session, GatewaySession.Saga saga) {
        List<String> open = new ArrayList<>();
        for (String shard : map.shardIds()) {
            if (RUNNING.equals(state(map, session, shard, saga))) {
                open.add(shard);
            }
        }
        return open;
    }

    /** O estado da saga no shard, ou {@code null} se ele não a conhece. */
    private String state(ShardMap map, GatewaySession session, String shard, GatewaySession.Saga saga) {
        String text = "SHOW SAGA TRANSACTION " + saga.name() + " " + quoted(saga.correlationId());
        AxonValue reply = context.send(map, Plan.Part.of(shard, text), session, false);
        if (!reply.isObject()) {
            return null;
        }
        AxonValue record = reply.asObject().get("saga");
        if (record == null || !record.isObject()) {
            return null;
        }
        AxonValue status = record.asObject().get("status");
        return status != null && status.isString() ? status.asString() : null;
    }

    private static AxonValue status(GatewaySession.Saga saga, String state) {
        Map<String, AxonValue> out = new LinkedHashMap<>();
        out.put("saga", AxonValue.str(saga.name()));
        out.put("correlation_id", AxonValue.str(saga.correlationId()));
        out.put("status", AxonValue.str(state));
        return AxonValue.object(out);
    }
}
