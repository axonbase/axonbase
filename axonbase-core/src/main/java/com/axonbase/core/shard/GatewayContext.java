package com.axonbase.core.shard;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.Executor;
import com.axonbase.core.hlc.ClockDriftException;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.parser.ast.Expr;
import com.axonbase.value.AxonValue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * O que os planejadores do gateway compartilham: os clientes dos shards, o relógio híbrido que viaja em toda mensagem e
 * a avaliação de expressões que não dependem de dados.
 */
final class GatewayContext {

    private final Function<ShardInfo, ShardClient> factory;
    private final Map<ShardInfo, ShardClient> clients = new ConcurrentHashMap<>();
    final HybridClock clock;
    final ShardGateway.Options options;
    /** Datastore vazio, só para avaliar expressões e funções no gateway. Nenhum dado é gravado nele. */
    final Datastore scratch = new Datastore(new MemoryBackend());

    GatewayContext(Function<ShardInfo, ShardClient> factory, HybridClock clock, ShardGateway.Options options) {
        this.factory = factory;
        this.clock = clock;
        this.options = options;
    }

    ShardClient client(ShardMap map, String shardId) {
        ShardInfo info = map.shards().stream().filter(s -> s.id().equals(shardId)).findFirst()
            .orElseThrow(() -> ShardException.refused("shard_unknown", shardId));
        return clients.computeIfAbsent(info, factory);
    }

    /** Envia um trecho a um shard com o carimbo e a credencial, e adota o carimbo da resposta. */
    AxonValue send(ShardMap map, Plan.Part part, GatewaySession session, boolean atomic) {
        Map<String, AxonValue> vars = new LinkedHashMap<>(session.vars());
        vars.putAll(part.vars());
        ShardRequest request = ShardRequest.of(session.namespace(), session.database(), part.text())
            .withVars(vars)
            .withAuthorization(session.authorization());
        if (atomic) {
            request = request.asAtomic();
        }
        if (part.partial()) {
            request = request.partial(part.filter());
        }
        ShardReply reply = client(map, part.shard()).execute(request.withStamp(clock.now()));
        observe(reply.stamp());
        return reply.value();
    }

    /**
     * Adota um carimbo recebido. Um carimbo além do desvio máximo não derruba uma resposta de um comando já executado:
     * o relógio local o recusa (e registra o alerta), e a resposta segue.
     */
    void observe(HlcTimestamp stamp) {
        try {
            clock.receive(stamp);
        } catch (ClockDriftException drift) {
            // o alerta já saiu pelo relógio
        }
    }

    AxonValue evaluate(Expr expr, GatewaySession session) {
        return new Executor(scratch).evaluate(expr, scope(session));
    }

    /** Uma sessão local com o banco e as variáveis do cliente, para avaliar expressões no gateway. */
    Session scope(GatewaySession session) {
        Session scope = Session.create();
        if (session.namespace() != null) {
            scope.namespace(session.namespace());
        }
        if (session.database() != null) {
            scope.database(session.database());
        }
        session.vars().forEach(scope.vars()::set);
        return scope;
    }
}
