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
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** Calcula no gateway uma expressão que não depende de dados (veja {@link GatewayExpressions}). */
    AxonValue evaluate(Expr expr, GatewaySession session) {
        if (!GatewayExpressions.local(expr, session.vars().keySet())) {
            throw ShardException.refused("not_local");
        }
        return new Executor(scratch).evaluate(expr, scope(session));
    }

    /**
     * Os valores de chave que a condição admite, calculados no gateway. Uma comparação com algo que só o shard sabe
     * (como {@code $auth}) não restringe: a instrução vai a todos.
     */
    Optional<List<AxonValue>> restriction(Expr condition, String field, GatewaySession session) {
        return ShardRouting.restriction(condition, field,
            expr -> GatewayExpressions.local(expr, session.vars().keySet()), expr -> evaluate(expr, session));
    }

    /**
     * Uma operação de manutenção do próprio gateway: o shard a executa sem permissões de tabela nem Data Rules, e só a
     * aceita de uma credencial root.
     */
    AxonValue sendSystem(ShardMap map, String shard, GatewaySession session, String text) {
        return sendSystem(map, shard, session, text, Map.of());
    }

    AxonValue sendSystem(ShardMap map, String shard, GatewaySession session, String text,
                         Map<String, AxonValue> vars) {
        // as definições e as perguntas ao catálogo não são de um banco
        boolean global = ShardRequest.DEFINITIONS.equals(text);
        ShardRequest request = ShardRequest.of(global ? null : session.namespace(),
                global ? null : session.database(), text)
            .withVars(vars)
            .withAuthorization(session.authorization()).asSystem();
        ShardReply reply = client(map, shard).execute(request.withStamp(clock.now()));
        observe(reply.stamp());
        return reply.value();
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
