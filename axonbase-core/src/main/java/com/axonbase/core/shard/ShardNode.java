package com.axonbase.core.shard;

import com.axonbase.core.Session;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.ReadConsistency;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.value.AxonValue;

import java.util.LinkedHashMap;

/**
 * Executa as mensagens do gateway num nó de shard. Só o líder atende, e leituras são linearizáveis, então o gateway lê
 * o que acabou de gravar. O carimbo recebido adianta o relógio do nó antes de executar (a regra de recebimento do
 * relógio híbrido), e a resposta leva o relógio depois da execução.
 */
public final class ShardNode implements ShardMember {

    private final Datastore datastore;
    private final ClusterRuntime cluster;
    private final HybridClock clock;

    /**
     * @param cluster runtime de consenso, ou {@code null} num shard de nó único
     * @param clock   o mesmo relógio que carimba os commits deste nó
     */
    public ShardNode(Datastore datastore, ClusterRuntime cluster, HybridClock clock) {
        this.datastore = datastore;
        this.cluster = cluster;
        this.clock = clock;
    }

    @Override
    public ShardReply handle(ShardRequest request) {
        return execute(request, Session.create());
    }

    /**
     * @param session sessão já autenticada pelo transporte; o namespace e o banco vêm da requisição
     */
    public ShardReply execute(ShardRequest request, Session session) {
        clock.receive(request.stamp());
        if (cluster != null) {
            if (cluster.role() != ClusterRole.LEADER) {
                throw cluster.notLeader();
            }
            if (!Datastore.isMutation(request.sql())) {
                cluster.awaitRead(ReadConsistency.LINEARIZABLE);
            }
        }
        session.namespace(request.namespace());
        session.database(request.database());
        AxonValue value = request.atomic() && cluster == null ? transaction(request, session) : run(request, session);
        return new ShardReply(value, clock.now());
    }

    private AxonValue run(ShardRequest request, Session session) {
        return datastore.execute(request.sql(), session, new LinkedHashMap<>(request.vars()));
    }

    /**
     * Num nó único o script vira uma transação explícita. No cluster o motor já confirma um comando avulso (o script
     * inteiro) numa transação, então nada precisa ser feito.
     */
    private AxonValue transaction(ShardRequest request, Session session) {
        datastore.beginSession(session);
        boolean committed = false;
        try {
            AxonValue value = run(request, session);
            datastore.commitSession(session);
            committed = true;
            return value;
        } finally {
            if (!committed && session.inTransaction()) {
                datastore.cancelSession(session);
            }
        }
    }
}
