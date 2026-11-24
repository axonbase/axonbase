package com.axonbase.core.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Depuração de funções, procedures e packages: transação revertida, permissões, limites e rastro. */
class DebugTest {

    private static final String GRAVA = """
        CREATE PROCEDURE grava(v) AS $$
        db.exec('CREATE item CONTENT {v: $v}', {'v': v})
        n = db.count('SELECT * FROM item')
        db.exec('CREATE item CONTENT {v: $w}', {'w': v + 1})
        return n
        $$""";

    private static Session session(Datastore ds) {
        Session s = Session.create();
        s.namespace("t");
        s.database("d");
        return s;
    }

    private static AxonValue claims(String roles) {
        return AxonValue.object(Map.of("id", AxonValue.str("u"), "scope", AxonValue.str("DATABASE"),
            "ns", AxonValue.str("t"), "db", AxonValue.str("d"), "roles", AxonValue.str(roles)));
    }

    private static DebugRequest req(String program, List<AxonValue> args, Set<Integer> bps, boolean entry,
                                    boolean commit, AxonValue auth) {
        return new DebugRequest("t", "d", auth, program, args, bps, entry, commit, false);
    }

    private static long rows(Datastore ds) {
        return ds.execute("SELECT * FROM item", session(ds), null).asArray().size();
    }

    /** Espera a sessão chegar ao estado (lendo os eventos). */
    static void espera(DebugSession s, String estado) {
        long after = 0;
        long fim = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < fim) {
            if (s.state().equals(estado)) {
                return;
            }
            after = s.events(after, 200).asObject().get("next").asLong();
        }
        throw new AssertionError("estado esperado " + estado + " mas esta " + s.state() + ": " + s.describe());
    }

    private static AxonValue pilhaTopo(DebugSession s) {
        return s.describe().asObject().get("pause").asObject().get("stack").asArray().get(0);
    }

    private void procedureRevertida(Datastore ds) {
        ds.execute(GRAVA, session(ds), null);
        DebugSession s = ds.programs().debug().start(req("grava", List.of(AxonValue.num(10L)), Set.of(3), false,
            false, null));
        espera(s, DebugSession.PAUSED);
        assertEquals(3, pilhaTopo(s).asObject().get("line").asLong());
        // dentro da transação da depuração a primeira escrita já está visível
        AxonValue visto = s.evaluate("db.count('SELECT * FROM item')");
        assertEquals("1", visto.asObject().get("value").asString());
        // e fora dela, não
        assertEquals(0, rows(ds));
        s.command("continue");
        espera(s, DebugSession.FINISHED);
        AxonValue d = s.describe();
        assertEquals(1, d.asObject().get("result").asLong());
        assertFalse(d.asObject().get("committed").asBool());
        assertEquals(0, rows(ds));
    }

    @Test
    void procedureComEscritaEhRevertidaNaMemoria() {
        procedureRevertida(Datastore.memory());
    }

    @Test
    void procedureComEscritaEhRevertidaNoRocksDb(@TempDir Path dir) throws Exception {
        Path path = Files.createDirectory(dir.resolve("rocks"));
        try (RocksDbBackend backend = new RocksDbBackend(path.toString())) {
            procedureRevertida(new Datastore(backend));
        }
    }

    @Test
    void commitVerdadeiroPersisteSoParaAdmin() {
        Datastore ds = Datastore.memory();
        ds.execute(GRAVA, session(ds), null);
        DebugException e = assertThrows(DebugException.class, () -> ds.programs().debug().start(
            req("grava", List.of(AxonValue.num(1L)), Set.of(), false, true, claims("operator"))));
        assertEquals(DebugException.Kind.FORBIDDEN, e.kind());
        assertEquals(DebugException.Kind.FORBIDDEN, assertThrows(DebugException.class, () ->
            ds.programs().debug().start(req("grava", List.of(AxonValue.num(1L)), Set.of(), false, false,
                claims("viewer")))).kind());
        assertEquals(0, rows(ds));

        DebugSession s = ds.programs().debug().start(req("grava", List.of(AxonValue.num(1L)), Set.of(), false, true,
            claims("admin")));
        espera(s, DebugSession.FINISHED);
        assertTrue(s.describe().asObject().get("committed").asBool());
        assertEquals(2, rows(ds));
    }

    @Test
    void operadorDepuraSemCommitEReverte() {
        Datastore ds = Datastore.memory();
        ds.execute(GRAVA, session(ds), null);
        DebugSession s = ds.programs().debug().start(req("grava", List.of(AxonValue.num(1L)), Set.of(), false,
            false, claims("operator")));
        espera(s, DebugSession.FINISHED);
        assertEquals(0, rows(ds));
    }

    @Test
    void erroNoProgramaReverteEMostraMensagem() {
        Datastore ds = Datastore.memory();
        ds.execute("CREATE PROCEDURE falha() AS $$\ndb.exec('CREATE item CONTENT {v: 1}')\nx = 1 / 0\n$$",
            session(ds), null);
        DebugSession s = ds.programs().debug().start(req("falha", List.of(), Set.of(), false, false, null));
        espera(s, DebugSession.ERROR);
        assertTrue(s.describe().asObject().get("error").asString().contains("ZeroDivisionError"),
            s.describe().toString());
        assertEquals(0, rows(ds));
    }

    @Test
    void funcaoContinuaSomenteLeitura() {
        Datastore ds = Datastore.memory();
        ds.execute("CREATE FUNCTION tenta() AS $$\ndb.exec('CREATE item CONTENT {v: 1}')\nreturn 1\n$$",
            session(ds), null);
        DebugSession s = ds.programs().debug().start(req("tenta", List.of(), Set.of(), false, false, null));
        espera(s, DebugSession.ERROR);
        assertTrue(s.describe().asObject().get("error").asString().contains("somente leitura"));
    }

    @Test
    void ddlNaoEhPermitidoSemCommit() {
        Datastore ds = Datastore.memory();
        ds.execute("CREATE PROCEDURE esquema() AS $$\ndb.exec('DEFINE TABLE nova SCHEMALESS')\n$$",
            session(ds), null);
        DebugSession s = ds.programs().debug().start(req("esquema", List.of(), Set.of(), false, false, null));
        espera(s, DebugSession.ERROR);
        assertTrue(s.describe().asObject().get("error").asString().contains("esquema"));
    }

    @Test
    void passosUsamLinhasDoCorpoDoUsuario() {
        Datastore ds = Datastore.memory();
        ds.execute(GRAVA, session(ds), null);
        DebugSession s = ds.programs().debug().start(req("grava", List.of(AxonValue.num(5L)), Set.of(), true,
            false, null));
        espera(s, DebugSession.PAUSED);
        assertEquals(1, pilhaTopo(s).asObject().get("line").asLong());
        s.command("stepOver");
        espera(s, DebugSession.PAUSED);
        assertEquals(2, pilhaTopo(s).asObject().get("line").asLong());
        List<AxonValue> locais = pilhaTopo(s).asObject().get("locals").asArray();
        assertTrue(locais.stream().anyMatch(v -> v.asObject().get("name").asString().equals("v")
            && v.asObject().get("value").asString().equals("5")));
        s.command("stop");
        assertEquals(DebugSession.STOPPED, s.state());
        assertEquals(0, rows(ds));
    }

    @Test
    void ociosidadeCancelaEReverte() throws Exception {
        Datastore ds = Datastore.memory();
        ds.execute(GRAVA, session(ds), null);
        ds.programs().debug().idleSeconds(1);
        DebugSession s = ds.programs().debug().start(req("grava", List.of(AxonValue.num(5L)), Set.of(3), false,
            false, null));
        espera(s, DebugSession.PAUSED);
        espera(s, DebugSession.TIMEOUT);
        assertEquals(1, ds.programs().debug().timeouts());
        assertEquals(0, ds.programs().debug().active());
        assertEquals(0, rows(ds));
    }

    @Test
    void limiteDeSessoesSimultaneas() {
        Datastore ds = Datastore.memory();
        ds.execute(GRAVA, session(ds), null);
        DebugManager m = ds.programs().debug();
        m.maxSessions(2);
        DebugSession a = m.start(req("grava", List.of(AxonValue.num(1L)), Set.of(3), false, false, null));
        DebugSession b = m.start(req("grava", List.of(AxonValue.num(2L)), Set.of(3), false, false, null));
        espera(a, DebugSession.PAUSED);
        espera(b, DebugSession.PAUSED);
        assertEquals(2, m.active());
        DebugException e = assertThrows(DebugException.class, () -> m.start(req("grava", List.of(AxonValue.num(3L)),
            Set.of(3), false, false, null)));
        assertEquals(DebugException.Kind.LIMIT, e.kind());
        a.command("continue");
        espera(a, DebugSession.FINISHED);
        // liberou uma vaga
        DebugSession c = m.start(req("grava", List.of(AxonValue.num(3L)), Set.of(), false, false, null));
        espera(c, DebugSession.FINISHED);
        assertTrue(m.remove(b.id()));
        assertEquals(0, m.active());
        assertNull(m.get(b.id()));
    }

    @Test
    void doisDepuradoresSimultaneosNaoSeInterferem() {
        Datastore ds = Datastore.memory();
        ds.execute(GRAVA, session(ds), null);
        DebugManager m = ds.programs().debug();
        DebugSession a = m.start(req("grava", List.of(AxonValue.num(100L)), Set.of(3), false, false, null));
        DebugSession b = m.start(req("grava", List.of(AxonValue.num(200L)), Set.of(4), false, false, null));
        espera(a, DebugSession.PAUSED);
        espera(b, DebugSession.PAUSED);
        // cada transação enxerga só a própria escrita
        assertEquals("1", a.evaluate("db.count('SELECT * FROM item')").asObject().get("value").asString());
        assertEquals("2", b.evaluate("db.count('SELECT * FROM item')").asObject().get("value").asString());
        assertEquals("100", a.evaluate("v").asObject().get("value").asString());
        assertEquals("200", b.evaluate("v").asObject().get("value").asString());
        a.command("continue");
        espera(a, DebugSession.FINISHED);
        assertEquals(DebugSession.PAUSED, b.state());
        b.command("continue");
        espera(b, DebugSession.FINISHED);
        assertEquals(0, rows(ds));
    }

    @Test
    void perfilDevolveLinhasEConsultas() {
        Datastore ds = Datastore.memory();
        ds.execute(GRAVA, session(ds), null);
        DebugSession s = ds.programs().debug().start(new DebugRequest("t", "d", null, "grava",
            List.of(AxonValue.num(1L)), Set.of(), false, false, true));
        espera(s, DebugSession.FINISHED);
        AxonValue p = s.describe().asObject().get("profile");
        assertNotNull(p);
        List<AxonValue> linhas = p.asObject().get("lines").asArray();
        assertEquals(4, linhas.size());
        assertEquals(1, linhas.get(0).asObject().get("line").asLong());
        assertEquals(1, linhas.get(0).asObject().get("count").asLong());
        List<AxonValue> qs = p.asObject().get("queries").asArray();
        assertEquals(3, qs.size());
        assertTrue(qs.get(0).asObject().get("sql").asString().startsWith("CREATE item"));
        assertEquals(1, qs.get(0).asObject().get("line").asLong());
        assertEquals(2, qs.get(1).asObject().get("line").asLong());
        assertEquals(1, qs.get(1).asObject().get("rows").asLong());
        assertTrue(qs.get(0).asObject().get("durationUs").asLong() >= 0);
    }

    @Test
    void packageEDepuradoPorPacoteFuncao() {
        Datastore ds = Datastore.memory();
        ds.execute("""
            CREATE PACKAGE util AS $$
            BASE = 10
            def somar(a, b):
                r = a + b + BASE
                return r
            $$""", session(ds), null);
        DebugSession s = ds.programs().debug().start(req("util::somar", List.of(AxonValue.num(1L), AxonValue.num(2L)),
            Set.of(4), false, false, null));
        espera(s, DebugSession.PAUSED);
        AxonValue pause = s.describe().asObject().get("pause");
        assertEquals("somar", pause.asObject().get("stack").asArray().get(0).asObject().get("function").asString());
        assertTrue(pause.asObject().get("globals").asArray().stream()
            .anyMatch(v -> v.asObject().get("name").asString().equals("BASE")));
        assertEquals("13", s.evaluate("a + b + BASE").asObject().get("value").asString());
        s.command("continue");
        espera(s, DebugSession.FINISHED);
        assertEquals(13, s.describe().asObject().get("result").asLong());
    }

    @Test
    void comandoForaDePausaEProgramaInexistente() {
        Datastore ds = Datastore.memory();
        ds.execute(GRAVA, session(ds), null);
        assertEquals(DebugException.Kind.NOT_FOUND, assertThrows(DebugException.class, () ->
            ds.programs().debug().start(req("nao_existe", List.of(), Set.of(), false, false, null))).kind());
        DebugSession s = ds.programs().debug().start(req("grava", List.of(AxonValue.num(1L)), Set.of(), false,
            false, null));
        espera(s, DebugSession.FINISHED);
        assertEquals(DebugException.Kind.CONFLICT, assertThrows(DebugException.class,
            () -> s.command("continue")).kind());
        assertEquals(DebugException.Kind.BAD_REQUEST, assertThrows(DebugException.class,
            () -> s.command("voar")).kind());
        assertNotEquals(0, s.events(0, 0).asObject().get("events").asArray().size());
    }
}
