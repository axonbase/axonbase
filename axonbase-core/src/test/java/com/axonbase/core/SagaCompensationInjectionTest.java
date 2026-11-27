package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

@DisplayName("SAGA: a compensação nunca transforma nome, chave ou valor em comando")
class SagaCompensationInjectionTest {

    private static final String TABELA = "t:x; CREATE invasor:1; --";

    private final Datastore ds = Datastore.memory();
    private final Session session = scope("t", "d");

    private static Session scope(String ns, String db) {
        Session s = Session.create();
        s.namespace(ns);
        s.database(db);
        return s;
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    private void begin(String correlation) {
        run("BEGIN SAGA z WITH CORRELATION '" + correlation + "'");
        run("LET $saga_corr = '" + correlation + "'");
    }

    private String cancel(String correlation) {
        return run("CANCEL SAGA z WITH CORRELATION '" + correlation + "'").asObject().get("status").asString();
    }

    private int count(String sql) {
        return run(sql).asArray().size();
    }

    @Test
    void nomeDeTabelaComSentencaDentroNaoViraComando() {
        begin("k1");
        run("CREATE `" + TABELA + "`:y CONTENT {}");

        assertEquals("FAILED", cancel("k1"));

        assertEquals(0, count("SELECT * FROM invasor"));
        assertEquals(0, count("SELECT * FROM `" + TABELA + "`"));
    }

    @Test
    void nomeDeTabelaNaoLevaACompensacaoParaOutroBanco() {
        String evil = "t:x; USE DB outro; CREATE invasor:1; --";
        begin("k2");
        run("CREATE `" + evil + "`:y CONTENT {}");

        assertEquals("FAILED", cancel("k2"));

        assertEquals(0, ds.execute("SELECT * FROM invasor", scope("t", "outro"), null).asArray().size());
    }

    @Test
    void nomesLegitimosComEspacoHifenECraseSaoDesfeitos() {
        begin("k3");
        run("CREATE `order items`:'a-1' SET v = 1");
        run("CREATE ⟨a`b⟩:'c d' SET v = 1");

        assertEquals("FAILED", cancel("k3"));

        assertEquals(0, count("SELECT * FROM `order items`"));
        assertEquals(0, count("SELECT * FROM ⟨a`b⟩"));
    }

    @Test
    void propriedadeDeArestaNaoViraComando() {
        String propriedade = "`n = 1; CREATE invasor:1; --`";
        run("RELATE p:1->conhece->p:2 SET " + propriedade + " = 1");
        begin("k4");
        run("RELATE p:1->conhece->p:2 SET " + propriedade + " = 2");

        assertEquals("FAILED", cancel("k4"));

        assertEquals(0, count("SELECT * FROM invasor"));
        assertFalse(run("UNRELATE p:1->conhece->p:2").asArray().isEmpty());
    }

    @Test
    void passoGravadoPorVersaoAnteriorNaoViraComando() {
        begin("k5");
        legacyStep("k5", 1, "b; CREATE invasor:1; --", "7");

        assertEquals("FAILED", cancel("k5"));

        assertEquals(0, count("SELECT * FROM invasor"));
    }

    @Test
    void passoGravadoPorVersaoAnteriorContinuaSendoDesfeito() {
        run("CREATE b:7 SET v = 1");
        begin("k6");
        legacyStep("k6", 1, "b", "7");

        assertEquals("FAILED", cancel("k6"));

        assertEquals(0, count("SELECT * FROM b"));
    }

    @Test
    void compensacaoRemotaEhUmaSoSentenca() throws Exception {
        String chave = "x; CREATE invasor:1; --";
        try (FakeParticipant participant = FakeParticipant.start()) {
            link(participant);
            begin("k7");
            run("CREATE p.t:'" + chave + "' CONTENT {}");
            int sent = participant.received().size();

            assertEquals("FAILED", cancel("k7"));

            List<String> compensation = participant.received().subList(sent, participant.received().size());
            String delete = compensation.get(0);
            List<Statement> statements = AxonQl.parse(delete).statements();
            assertEquals(1, statements.size(), delete);
            Statement.Delete parsed = assertInstanceOf(Statement.Delete.class, statements.get(0), delete);
            Expr.RecordId target = assertInstanceOf(Expr.RecordId.class, parsed.target(), delete);
            assertEquals(new Expr.Ident("t"), target.table(), delete);
            assertEquals(new Expr.Literal(AxonValue.str(chave)), target.key(), delete);
        }
    }

    @Test
    void nomeNaoSimplesNaoSegueParaOParticipante() throws Exception {
        try (FakeParticipant participant = FakeParticipant.start()) {
            link(participant);
            begin("k8");
            // a escrita pelo link já recusa o nome: o passo só existe se uma versão anterior o gravou
            step("k8", 1, "t", "p", TABELA, "1");

            assertThrows(RuntimeException.class, () -> cancel("k8"));

            assertEquals(List.of(), participant.received());
        }
    }

    private void link(FakeParticipant participant) {
        run("DEFINE DATABASE LINK \"p\" CONNECT BY \"" + participant.url()
            + "\" WITH ns = \"t\" db = \"p\" user = \"\" password = \"\"");
    }

    /** Um passo como a rc1 gravava: só o texto cru da chave. */
    private void legacyStep(String correlation, int order, String table, String key) {
        step(correlation, order, "", "local", table, key);
    }

    private void step(String correlation, int order, String linkNamespace, String linkName, String table, String key) {
        ds.execute("CREATE saga_step CONTENT {correlation_id: \"" + correlation + "\", step_order: " + order
            + ", step_ns: \"t\", step_db: \"d\", link_ns: \"" + linkNamespace + "\", link_name: \"" + linkName
            + "\", table_name: \"" + table + "\", record_key: \"" + key
            + "\", before: {}, operation: \"CREATE\", status: \"PREPARED\"}",
            scope("system", "saga"), null);
    }
}
