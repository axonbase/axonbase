package com.axonbase.core.shard;

import com.axonbase.core.Session;
import com.axonbase.core.catalog.Catalog;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.ReadConsistency;
import com.axonbase.core.control.ControlCommand;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
     * @param caller sessão já autenticada pelo transporte; o namespace e o banco vêm da requisição
     */
    public ShardReply execute(ShardRequest request, Session caller) {
        // manutenção roda sem usuário: o transporte já conferiu que a credencial é root
        Session session = request.system() ? Session.create() : caller;
        if (request.system()) {
            // o movimentador copia registros que já existem: um resto de tentativa anterior é substituído
            session.restoring(true);
        }
        clock.receive(request.stamp());
        if (ShardRequest.AUTHENTICATE.equals(request.sql())) {
            // qualquer nó responde: não lê nem grava dados
            return new ShardReply(AxonValue.bool(true), clock.now());
        }
        boolean definitions = ShardRequest.DEFINITIONS.equals(request.sql());
        boolean permissions = ShardRequest.PERMISSIONS.equals(request.sql());
        if (permissions && !request.system()) {
            throw ShardException.refused("system_only");
        }
        if (cluster != null) {
            if (cluster.role() != ClusterRole.LEADER) {
                throw cluster.notLeader();
            }
            if (definitions || permissions || !Datastore.isMutation(request.sql())) {
                cluster.awaitRead(ReadConsistency.LINEARIZABLE);
            }
        }
        if (definitions) {
            return new ShardReply(definitions(), clock.now());
        }
        if (permissions) {
            return new ShardReply(AxonValue.bool(hasPermissions(request)), clock.now());
        }
        session.namespace(request.namespace());
        session.database(request.database());
        AxonValue value = request.atomic() && cluster == null ? transaction(request, session) : run(request, session);
        return new ShardReply(value, clock.now());
    }

    /** O plano de controle do nó: cada definição com o banco dela, na ordem de reaplicação. */
    private AxonValue definitions() {
        List<AxonValue> out = new ArrayList<>();
        for (ControlCommand command : datastore.controlSnapshot().commands()) {
            if (command.kind() == ControlCommand.Kind.JKS) {
                continue;
            }
            Map<String, AxonValue> item = new LinkedHashMap<>();
            item.put("ns", AxonValue.str(command.namespace()));
            item.put("db", AxonValue.str(command.database()));
            item.put("definition", AxonValue.str(command.definition()));
            out.add(AxonValue.object(item));
        }
        return AxonValue.array(out);
    }

    private boolean hasPermissions(ShardRequest request) {
        Database database = datastore.findDatabase(request.namespace(), request.database());
        AxonValue table = request.vars().get("table");
        Catalog.TableDef def = database == null || table == null ? null : database.catalog().table(table.asString());
        return def != null && GatewayRules.restricts(def.permissions());
    }

    private AxonValue run(ShardRequest request, Session session) {
        if (request.partial()) {
            return datastore.executePartial(request.sql(), select(request), session,
                new LinkedHashMap<>(request.vars()));
        }
        return datastore.execute(request.sql(), session, new LinkedHashMap<>(request.vars()));
    }

    /** O SELECT de uma requisição parcial, restrito aos slots do shard quando há uma movimentação. */
    private static Statement.Select select(ShardRequest request) {
        List<Statement> statements = AxonQl.parse(request.sql()).statements();
        if (statements.size() != 1 || !(statements.get(0) instanceof Statement.Select select)) {
            throw ShardException.refused("partial_select");
        }
        SlotFilter filter = request.filter();
        if (filter == null) {
            return select;
        }
        Expr owned = new Expr.Call("shard::owned", List.of(new Expr.Ident(filter.field()),
            new Expr.Literal(AxonValue.str(filter.table())), new Expr.Literal(AxonValue.str(filter.field())),
            new Expr.Literal(AxonValue.str(SlotRange.format(filter.ranges())))));
        Expr condition = select.cond() == null ? owned : new Expr.Binary(Expr.BinaryOp.AND, select.cond(), owned);
        return new Statement.Select(select.fields(), select.valueOnly(), select.only(), select.from(), condition,
            select.orders(), select.group(), select.limit(), select.start(), select.fetch(), select.joins(),
            select.timeTravel(), select.having(), select.groupAll());
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
