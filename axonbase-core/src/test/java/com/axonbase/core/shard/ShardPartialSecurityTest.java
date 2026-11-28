package com.axonbase.core.shard;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.audit.AiAuditDef;
import com.axonbase.core.audit.AiAuditRule;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("Shards: o modo parcial respeita a auditoria por IA e o mascaramento")
class ShardPartialSecurityTest {

    private final Datastore ds = Datastore.memory();
    private final ShardNode node = new ShardNode(ds, null, HybridClock.system());

    private static Session session() {
        Session session = Session.create();
        session.namespace("n");
        session.database("d");
        return session;
    }

    /** Uma sessão autenticada com os atributos dados em pares de nome e valor. */
    private static Session user(String... claims) {
        Map<String, AxonValue> auth = new LinkedHashMap<>();
        for (int i = 0; i < claims.length; i += 2) {
            auth.put(claims[i], AxonValue.str(claims[i + 1]));
        }
        Session session = Session.create();
        session.auth(AxonValue.object(auth));
        return session;
    }

    private AxonValue partial(String sql, Session caller) {
        return node.execute(ShardRequest.of("n", "d", sql).partial(null), caller).value();
    }

    @BeforeEach
    void data() {
        ds.createDatabase("n", "d");
        ds.execute("CREATE cliente:1 SET cpf = '222', nome = 'bia'", session(), null);
        ds.execute("CREATE cliente:2 SET cpf = '111', nome = 'ana'", session(), null);
    }

    @Test
    void consultaParcialPassaPelaAuditoriaPorIa() {
        ds.requireDatabase(session()).catalog().defineAudit(new AiAuditDef("protege", "aviso", "perigo", List.of(),
            List.of(new AiAuditRule("keyword", null, null, List.of("CPF"), null)), 1L, "admin"));
        Session eva = user("user", "eva", "audit", "protege");

        AxonError danger = assertThrows(AxonError.class, () -> partial("SELECT cpf FROM cliente", eva));
        assertEquals(-32002, danger.code());
        // a consulta perigosa bloqueia o usuário: nem uma consulta inofensiva passa depois
        AxonError blocked = assertThrows(AxonError.class, () -> partial("SELECT nome FROM cliente", eva));
        assertEquals(-32002, blocked.code());
    }

    @Test
    void ordenarPorColunaMascaradaERecusado() {
        ds.execute("CREATE DATA RULE sigilo APPLY true MASK FIELDS WITH 'cpf'", session(), null);
        Session caio = user("user", "caio", "data_rules", "sigilo");

        for (String sql : List.of("SELECT nome FROM cliente ORDER BY cpf",
            "SELECT string::concat(cpf) AS apelido FROM cliente ORDER BY apelido",
            "SELECT nome AS cpf FROM cliente ORDER BY cpf")) {
            assertThrows(AxonError.class, () -> partial(sql, caio), sql);
        }
    }

    @Test
    void mascaraSobreOIdRecusaOModoParcialPorqueAChaveVaiNaResposta() {
        ds.execute("CREATE DATA RULE semid APPLY true MASK FIELDS WITH 'id'", session(), null);

        assertThrows(AxonError.class, () -> partial("SELECT nome FROM cliente", user("data_rules", "semid")));
    }

    @Test
    void ordenarPorColunaLivreContinuaValendoEAMascaraSegueNaLinha() {
        ds.execute("CREATE DATA RULE sigilo APPLY true MASK FIELDS WITH 'cpf'", session(), null);

        List<AxonValue> rows = partial("SELECT * FROM cliente ORDER BY nome", user("data_rules", "sigilo"))
            .asObject().get("items").asArray();

        assertEquals(List.of(AxonValue.str("ana")), rows.get(0).asObject().get("o").asArray());
        assertEquals(AxonValue.str("***"), rows.get(0).asObject().get("r").asObject().get("cpf"));
    }
}
