package com.axonbase.core.pl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.control.ControlCommand;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.QueryContext;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend;
import com.axonbase.value.AxonValue;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** FUNCTION, PROCEDURE e PACKAGE do AxonPL dentro do motor. */
class ProgramsTest {

    private final Datastore ds = Datastore.memory();
    private final Session s = session(ds);

    static Session session(Datastore ds) {
        Session s = Session.create();
        s.namespace("t");
        s.database("d");
        return s;
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, s, null);
    }

    private static AxonError error(Datastore ds, Session s, String sql) {
        return assertThrows(AxonError.class, () -> ds.execute(sql, s, null));
    }

    private AxonError error(String sql) {
        return error(ds, s, sql);
    }

    private long count(String table) {
        return run("SELECT * FROM " + table).asArray().size();
    }

    // ------------------------------------------------------------------
    // funcoes
    // ------------------------------------------------------------------

    @Test
    void funcaoEmSelectWhereEAssert() {
        run("CREATE item CONTENT {nome: 'a', preco: 100}");
        run("CREATE item CONTENT {nome: 'b', preco: 40}");
        run("""
            CREATE FUNCTION desconto(valor, pct int = 10) RETURNS number AS $$
                return valor * (100 - pct) / 100
            $$""");
        AxonValue rows = run("SELECT nome, desconto(preco) AS final FROM item ORDER BY nome");
        assertEquals(90, rows.asArray().get(0).asObject().get("final").asLong());
        assertEquals(36, rows.asArray().get(1).asObject().get("final").asLong());
        assertEquals(1, run("SELECT * FROM item WHERE desconto(preco, 50) > 40").asArray().size());
        assertEquals(45, run("RETURN fn::desconto(50)").asLong());
        assertEquals(40, run("RETURN desconto(50, 20)").asLong());

        run("DEFINE FIELD qtd ON TABLE item ASSERT desconto($value, 0) >= 0");
        run("CREATE item CONTENT {nome: 'c', preco: 1, qtd: 3}");
        assertThrows(AxonError.class, () -> run("CREATE item CONTENT {nome: 'd', preco: 1, qtd: -3}"));
    }

    @Test
    void tiposDosParametrosSaoValidadosEConvertidos() {
        run("CREATE FUNCTION mais1(x int) RETURNS int AS $$ return x + 1 $$");
        assertEquals(6, run("RETURN mais1('5')").asLong());
        assertEquals(6, run("RETURN mais1(5.0)").asLong());
        AxonError e = error("RETURN mais1('abc')");
        assertTrue(e.getMessage().contains("argumento 'x'") && e.getMessage().contains("int"), e.getMessage());
        assertTrue(error("RETURN mais1()").getMessage().contains("falta o argumento 'x'"));
        assertTrue(error("RETURN mais1(1, 2)").getMessage().contains("no maximo 1"));
        assertTrue(error("CREATE FUNCTION g(x quadrado) AS $$ return 1 $$").getMessage().contains("tipo desconhecido"));
    }

    @Test
    void substituirIncrementaVersaoERecusaDuplicata() {
        run("CREATE FUNCTION f() AS $$ return 1 $$");
        assertTrue(error("CREATE FUNCTION f() AS $$ return 2 $$").getMessage().contains("ja existe"));
        assertEquals(1, run("RETURN f()").asLong());
        run("CREATE OR REPLACE FUNCTION f() AS $$ return 2 $$");
        assertEquals(2, run("RETURN f()").asLong());
        AxonValue info = run("INFO FOR FUNCTION f");
        assertEquals(2, info.asObject().get("version").asLong());
        assertEquals("return 2", info.asObject().get("source").asString());
        assertTrue(error("CREATE OR REPLACE PROCEDURE f() AS $$ return 3 $$").getMessage().contains("ja e"));
    }

    @Test
    void removerFuncao() {
        run("CREATE FUNCTION f() AS $$ return 1 $$");
        run("DROP FUNCTION f");
        assertTrue(error("RETURN f()").getMessage().toLowerCase().contains("f"));
        assertTrue(error("DROP FUNCTION f").getMessage().contains("nao existe"));
    }

    @Test
    void infoDaFuncaoEDoBanco() {
        run("""
            CREATE FUNCTION dobro(x int = 2, nome string = 'a') RETURNS int AS $$
                \"\"\"Dobra o valor.\"\"\"
                return x * 2
            $$""");
        run("CREATE PROCEDURE grava(v) AS $$ db.exec('CREATE item CONTENT {v: $v}', {'v': v}) $$");
        run("CREATE PACKAGE util AS $$\nTAXA = 7\nPRIVADA = 1\n_oculta = 2\ndef soma(a, b):\n    return a + b\ndef _interna():\n    return 0\n$$");
        Map<String, AxonValue> info = run("INFO FOR FUNCTION dobro").asObject();
        assertEquals("dobro", info.get("name").asString());
        assertEquals("FUNCTION", info.get("kind").asString());
        assertEquals("Dobra o valor.", info.get("doc").asString());
        assertEquals("int", info.get("returns").asString());
        assertEquals(2, info.get("params").asArray().size());
        assertEquals("x", info.get("params").asArray().get(0).asObject().get("name").asString());
        assertEquals("int", info.get("params").asArray().get(0).asObject().get("type").asString());
        assertEquals(2, info.get("params").asArray().get(0).asObject().get("default").asLong());
        assertEquals(1, info.get("version").asLong());
        assertTrue(info.containsKey("created") && info.containsKey("updated") && info.containsKey("lines"));
        Map<String, AxonValue> pkg = run("INFO FOR PACKAGE util").asObject();
        assertEquals("TAXA", pkg.get("constants").asArray().get(0).asString());
        assertEquals(2, pkg.get("constants").asArray().size());
        assertEquals("soma", pkg.get("functions").asArray().get(0).asObject().get("name").asString());
        assertEquals(1, pkg.get("functions").asArray().size());
        Map<String, AxonValue> db = run("INFO FOR DATABASE").asObject();
        assertTrue(db.containsKey("details") && db.containsKey("analyzers") && db.containsKey("embeddings"));
        assertTrue(db.get("functions").asObject().containsKey("dobro"));
        assertTrue(db.get("procedures").asObject().containsKey("grava"));
        assertEquals("soma", db.get("packages").asObject().get("util").asObject().get("functions").asArray().get(0).asString());
    }

    // ------------------------------------------------------------------
    // packages
    // ------------------------------------------------------------------

    @Test
    void packageComConstantesEImportDeOutro() {
        run("CREATE PACKAGE base AS $$\nIMPOSTO = 10\ndef com_imposto(v):\n    return v + v * IMPOSTO / 100\n$$");
        run("CREATE PACKAGE loja AS $$\nimport base\ndef total(v):\n    return base.com_imposto(v) + 1\n$$");
        assertEquals(111, run("RETURN loja::total(100)").asLong());
        assertEquals(110, run("RETURN base::com_imposto(100)").asLong());
        assertEquals(110, run("CALL base::com_imposto(100)").asLong());
        assertTrue(error("RETURN base::_x(1)").getMessage().contains("funcao publica"));
        assertTrue(error("DROP PACKAGE base").getMessage().contains("importado"));
    }

    @Test
    void packageCompartilhaGlobaisEhRecarregadoNoReplace() {
        run("CREATE PACKAGE cont AS $$\nn = 0\ndef inc():\n    global n\n    n = n + 1\n    return n\n$$");
        assertEquals(1, run("RETURN cont::inc()").asLong());
        assertEquals(2, run("RETURN cont::inc()").asLong());
        run("CREATE OR REPLACE PACKAGE cont AS $$\nn = 100\ndef inc():\n    global n\n    n = n + 1\n    return n\n$$");
        assertEquals(101, run("RETURN cont::inc()").asLong());
    }

    // ------------------------------------------------------------------
    // db
    // ------------------------------------------------------------------

    @Test
    void dbQueryOneCountEParametros() {
        run("CREATE p CONTENT {n: 1, g: 'a'}");
        run("CREATE p CONTENT {n: 2, g: 'a'}");
        run("CREATE p CONTENT {n: 3, g: 'b'}");
        run("""
            CREATE FUNCTION le(grupo) AS $$
                rows = db.query("SELECT * FROM p WHERE g = $g ORDER BY n", {"g": grupo})
                primeiro = db.one("SELECT * FROM p WHERE g = $g ORDER BY n", {"g": grupo})
                nenhum = db.one("SELECT * FROM p WHERE g = $g", {"g": "zzz"})
                total = db.count("SELECT * FROM p WHERE g = $g", {"g": grupo})
                return {"n": len(rows), "primeiro": primeiro["n"], "nenhum": nenhum, "total": total,
                        "r": db.rid("p", 7)}
            $$""");
        s.vars().set("g", AxonValue.str("original"));
        Map<String, AxonValue> r = run("RETURN le('a')").asObject();
        assertEquals(2, r.get("n").asLong());
        assertEquals(1, r.get("primeiro").asLong());
        assertTrue(r.get("nenhum").isNull() || r.get("nenhum").isNone());
        assertEquals(2, r.get("total").asLong());
        assertEquals("p", r.get("r").asRecordId().table());
        assertEquals("original", s.vars().get("g").asString(), "$g deve ser restaurado");
        run("RETURN le('b')");
        assertFalse(s.vars().contains("h"));
        run("CREATE FUNCTION le2() AS $$ return db.count('SELECT count() FROM p GROUP ALL') $$");
        assertEquals(3, run("RETURN le2()").asLong());
    }

    @Test
    void funcaoNaoEscreveMasProcedureEscreve() {
        run("CREATE FUNCTION ruim() AS $$ db.exec(\"CREATE x CONTENT {a: 1}\") $$");
        AxonError e = error("RETURN ruim()");
        assertTrue(e.getMessage().contains("somente leitura"), e.getMessage());
        run("CREATE FUNCTION ruim2() AS $$ return db.query(\"CREATE x CONTENT {a: 1}\") $$");
        assertTrue(error("RETURN ruim2()").getMessage().contains("somente leitura"));
        assertEquals(0, count("x"));
        run("CREATE PROCEDURE bom() AS $$ return db.exec(\"CREATE x CONTENT {a: 1}\") $$");
        run("CALL bom()");
        assertEquals(1, count("x"));
        assertTrue(error("RETURN bom()").getMessage().contains("CALL"));
    }

    @Test
    void procedureNaoMudaEscopoNemControlaTransacao() {
        run("CREATE PROCEDURE p1() AS $$ db.exec('USE NS outro') $$");
        assertTrue(error("CALL p1()").getMessage().contains("USE"));
        run("CREATE PROCEDURE p2() AS $$ db.exec('COMMIT') $$");
        assertTrue(error("CALL p2()").getMessage().contains("BEGIN, COMMIT"));
        assertEquals("t", s.namespace());
    }

    // ------------------------------------------------------------------
    // atomicidade
    // ------------------------------------------------------------------

    private static final String GRAVA = """
        CREATE PROCEDURE grava(total, falha_em = -1) AS $$
            for i in range(total):
                if i == falha_em:
                    raise ValueError("falha no registro " + str(i))
                db.exec("CREATE item CONTENT {code: $c}", {"c": i})
            return total
        $$""";

    @Test
    void procedureEAtomica() {
        run("DEFINE INDEX ux ON TABLE item COLUMNS code UNIQUE");
        run(GRAVA);
        AxonError e = error("CALL grava(5, 3)");
        assertTrue(e.getMessage().contains("falha no registro 3"), e.getMessage());
        assertTrue(e.getMessage().contains("Traceback"), e.getMessage());
        assertEquals(0, count("item"), "nada pode permanecer");
        // o indice unico voltou ao estado inicial: os mesmos codigos podem ser gravados
        assertEquals(3, run("CALL grava(3)").asLong());
        assertEquals(3, count("item"));
        assertEquals(1, run("SELECT * FROM item WHERE code = 2").asArray().size());
        assertThrows(AxonError.class, () -> run("CREATE item CONTENT {code: 2}"));
        assertFalse(s.inTransaction());
    }

    @Test
    void procedureDentroDeTransacaoExplicita() {
        run("CREATE item CONTENT {code: 100}");
        run(GRAVA);
        run("BEGIN");
        run("CREATE item CONTENT {code: 200}");
        error("CALL grava(5, 2)");
        assertTrue(s.inTransaction(), "a transacao do usuario continua aberta");
        assertEquals(2, count("item"), "a falha desfez so a procedure");
        run("CALL grava(2)");
        run("CANCEL");
        assertEquals(1, count("item"));
        run("BEGIN");
        run("CALL grava(2)");
        run("COMMIT");
        assertEquals(3, count("item"));
    }

    // ------------------------------------------------------------------
    // limites
    // ------------------------------------------------------------------

    @Test
    void lacoInfinitoEstouraPassos() {
        run("CREATE FUNCTION laco() AS $$\nwhile True:\n    pass\n$$");
        AxonError e = error("RETURN laco()");
        assertEquals(ProgramService.PL_LIMIT, e.code());
        assertTrue(e.getMessage().contains("limite de passos"), e.getMessage());
        assertTrue(ds.programs().stats().limitErrors() >= 1);
    }

    @Test
    void prazoDeExecucao() {
        ds.programs().timeoutMs(150);
        run("CREATE PROCEDURE lento() AS $$\nwhile True:\n    db.count('RETURN 1')\n$$");
        long t0 = System.nanoTime();
        AxonError e = error("CALL lento()");
        assertTrue(e.getMessage().contains("prazo"), e.getMessage());
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 3000);
    }

    @Test
    void cancelamentoPelaAtividade() throws Exception {
        run("CREATE PROCEDURE lento() AS $$\nwhile True:\n    db.count('RETURN 1')\n$$");
        QueryContext.Handle handle = new QueryContext.Handle("h1");
        AtomicReference<Throwable> out = new AtomicReference<>();
        Thread t = new Thread(() -> {
            QueryContext.Handle prev = QueryContext.attach(handle);
            try {
                ds.execute("CALL lento()", s, null);
            } catch (Throwable e) {
                out.set(e);
            } finally {
                QueryContext.detach(prev);
            }
        });
        t.start();
        Thread.sleep(200);
        handle.cancel("teste");
        t.join(5000);
        assertFalse(t.isAlive());
        assertTrue(out.get() instanceof AxonError ae && ae.code() == QueryContext.CANCELLED, String.valueOf(out.get()));
    }

    @Test
    void recursaoEntreSqlEProgramaELimitada() {
        run("CREATE FUNCTION rec(n) AS $$ return db.one('RETURN rec($n)', {'n': n + 1}) $$");
        AxonError e = error("RETURN rec(0)");
        assertTrue(e.getMessage().contains("16 niveis"), e.getMessage());
        assertFalse(s.vars().contains("n"));
    }

    @Test
    void erroDeProgramaTemTracebackSemCaminhosJava() {
        run("CREATE FUNCTION a(x) AS $$\n\ndef interna(y):\n    return 1 / y\nreturn interna(x)\n$$");
        AxonError e = error("RETURN a(0)");
        assertEquals(ProgramService.PL_ERROR, e.code());
        assertTrue(e.getMessage().contains("ZeroDivisionError"), e.getMessage());
        assertTrue(e.getMessage().contains("linha 2"), e.getMessage());
        assertFalse(e.getMessage().contains("java.") || e.getMessage().contains(".java"));
    }

    @Test
    void erroDeSintaxeNoCreate() {
        AxonError e = error("CREATE FUNCTION f() AS $$\nreturn (1 +\n$$");
        assertTrue(e.getMessage().contains("sintaxe"), e.getMessage());
        assertTrue(error("RETURN f()").getMessage().length() > 0);
    }

    @Test
    void nomesReservadosSaoRecusados() {
        assertTrue(error("CREATE FUNCTION count() AS $$ return 1 $$").getMessage().contains("reservado"));
        assertTrue(error("CREATE FUNCTION now() AS $$ return 1 $$").getMessage().contains("reservado"));
        assertTrue(error("CREATE PACKAGE math AS $$ X = 1 $$").getMessage().contains("reservado"));
        assertTrue(error("CREATE PACKAGE string AS $$ X = 1 $$").getMessage().contains("reservado"));
        assertTrue(error("CREATE PACKAGE db AS $$ X = 1 $$").getMessage().contains("reservado"));
        assertTrue(error("CREATE FUNCTION fn() AS $$ return 1 $$").getMessage().contains("reservado"));
    }

    @Test
    void logVaiParaOBufferEOSink() {
        StringBuilder sink = new StringBuilder();
        ds.programs().logSink((level, msg) -> sink.append(level).append('|').append(msg).append(';'));
        run("CREATE FUNCTION fala(x) AS $$\nlog.info('ola {}', x)\nlog.warn('cuidado')\nprint('direto')\nreturn x\n$$");
        run("RETURN fala('mundo')");
        assertTrue(ds.programs().lastCallLog().contains("INFO: ola mundo"), ds.programs().lastCallLog().toString());
        assertTrue(ds.programs().lastCallLog().contains("WARN: cuidado"));
        assertTrue(sink.toString().contains("INFO|[fala] ola mundo"), sink.toString());
        assertTrue(ds.programs().recentLog().size() >= 3);
    }

    // ------------------------------------------------------------------
    // replay e persistencia
    // ------------------------------------------------------------------

    @Test
    void replayMantemTudoEObjetoQuebradoNaoDerrubaASubida() {
        MemoryBackend backend = new MemoryBackend();
        Datastore a = new Datastore(backend);
        Session sa = session(a);
        a.execute("CREATE FUNCTION f(x int = 3) RETURNS int AS $$ return x * 2 $$", sa, null);
        a.execute("CREATE OR REPLACE FUNCTION f(x int = 3) RETURNS int AS $$ return x * 3 $$", sa, null);
        a.execute("CREATE PACKAGE util AS $$ K = 5\ndef g():\n    return K $$", sa, null);
        a.execute("CREATE PROCEDURE p() AS $$ db.exec(\"CREATE r CONTENT {a: 1}\") $$", sa, null);
        a.execute("CREATE FUNCTION apagada() AS $$ return 1 $$", sa, null);
        a.execute("DROP FUNCTION apagada", sa, null);
        a.applyControl(ControlCommand.of(ControlCommand.Kind.FUNCTION, "t", "d", "quebrada",
            "CREATE OR REPLACE FUNCTION quebrada() AS $$ return ( $$"));

        Datastore b = new Datastore(backend);
        Session sb = session(b);
        assertEquals(12, b.execute("RETURN f(4)", sb, null).asLong());
        assertEquals(9, b.execute("RETURN f()", sb, null).asLong());
        assertEquals(2, b.execute("INFO FOR FUNCTION f", sb, null).asObject().get("version").asLong());
        assertEquals(5, b.execute("RETURN util::g()", sb, null).asLong());
        b.execute("CALL p()", sb, null);
        assertEquals(1, b.execute("SELECT * FROM r", sb, null).asArray().size());
        assertTrue(error(b, sb, "INFO FOR FUNCTION apagada").getMessage().contains("nao existe"));
        Map<String, AxonValue> broken = b.execute("INFO FOR FUNCTION quebrada", sb, null).asObject();
        assertTrue(broken.get("broken").asBool());
        assertTrue(broken.get("error").asString().contains("sintaxe"));
        assertTrue(error(b, sb, "RETURN quebrada()").getMessage().contains("quebrado"));
        // substituir a quebrada conserta
        b.execute("CREATE OR REPLACE FUNCTION quebrada() AS $$ return 1 $$", sb, null);
        assertEquals(1, b.execute("RETURN quebrada()", sb, null).asLong());
    }

    @Test
    void corpoComCifraoERoundTripNoPlanoDeControle() {
        MemoryBackend backend = new MemoryBackend();
        Datastore a = new Datastore(backend);
        Session sa = session(a);
        a.execute("CREATE FUNCTION c() AS $x$ s = '$$ -- nao e comentario'\nreturn s $x$", sa, null);
        Datastore b = new Datastore(backend);
        assertEquals("$$ -- nao e comentario", b.execute("RETURN c()", session(b), null).asString());
    }

    @Test
    void dumpEImportacaoLevamOsProgramas() {
        run("CREATE FUNCTION f(x int = 2) AS $$ return x + 1 $$");
        run("CREATE PACKAGE util AS $$ K = 9 $$");
        String dump = ds.exportDatabase("t", "d");
        assertTrue(dump.contains("CREATE OR REPLACE FUNCTION f"), dump);
        Datastore other = Datastore.memory();
        other.importDatabase("t", "d", dump);
        assertEquals(3, other.execute("RETURN f()", session(other), null).asLong());
        assertEquals(1, other.execute("INFO FOR FUNCTION f", session(other), null).asObject().get("version").asLong());
    }

    @Test
    void comRocksDb(@TempDir Path dir) throws Exception {
        Path path = Files.createDirectory(dir.resolve("rocks"));
        Session sa;
        try (RocksDbBackend backend = new RocksDbBackend(path.toString())) {
            Datastore a = new Datastore(backend);
            sa = session(a);
            a.execute("DEFINE INDEX ux ON TABLE item COLUMNS code UNIQUE", sa, null);
            a.execute(GRAVA, sa, null);
            assertThrows(AxonError.class, () -> a.execute("CALL grava(5, 3)", sa, null));
            assertEquals(0, a.execute("SELECT * FROM item", sa, null).asArray().size());
            a.execute("CALL grava(4)", sa, null);
        }
        try (RocksDbBackend backend = new RocksDbBackend(path.toString())) {
            Datastore b = new Datastore(backend);
            Session sb = session(b);
            assertEquals(4, b.execute("SELECT * FROM item", sb, null).asArray().size());
            assertEquals(1, b.execute("SELECT * FROM item WHERE code = 3", sb, null).asArray().size());
            assertEquals(4, b.execute("CALL grava(0) ; RETURN 4", sb, null).asLong());
        }
    }

    // ------------------------------------------------------------------
    // seguranca
    // ------------------------------------------------------------------

    private static AxonValue claims(String id, String roles, String rules) {
        return AxonValue.object(Map.of("id", AxonValue.str(id), "scope", AxonValue.str("DATABASE"),
            "ns", AxonValue.str("t"), "db", AxonValue.str("d"), "roles", AxonValue.str(roles),
            "data_rules", AxonValue.str(rules)));
    }

    private Session as(String id, String roles, String rules) {
        Session u = session(ds);
        u.auth(claims(id, roles, rules));
        return u;
    }

    @Test
    void definerVersusInvokerComUsuarioDePapelBaixo() {
        Session admin = as("dono", "admin", "");
        ds.execute("CREATE doc CONTENT {n: 10}", admin, null);
        ds.execute("CREATE doc CONTENT {n: 30}", admin, null);
        ds.execute("CREATE DATA RULE grandes APPLY n > 20", admin, null);
        ds.execute("CREATE FUNCTION via_invoker() AS $$ return db.count('SELECT * FROM doc') $$", admin, null);
        ds.execute("CREATE FUNCTION via_definer() SECURITY DEFINER AS $$ return db.count('SELECT * FROM doc') $$",
            admin, null);
        Session baixo = as("ze", "operator", "grandes");
        assertEquals(1, ds.execute("RETURN via_invoker()", baixo, null).asLong());
        assertEquals(2, ds.execute("RETURN via_definer()", baixo, null).asLong());
        assertEquals(1, ds.execute("SELECT * FROM doc", baixo, null).asArray().size());
        assertEquals("ze", baixo.auth().asObject().get("id").asString(), "a identidade volta ao normal");
        // quem nao e admin nao cria DEFINER
        AxonError e = error(ds, baixo, "CREATE FUNCTION x() SECURITY DEFINER AS $$ return 1 $$");
        assertTrue(e.getMessage().contains("admin"), e.getMessage());
    }

    @Test
    void dataRulesEPermissoesValemDentroDoDbExec() {
        Session admin = as("dono", "admin", "");
        ds.execute("CREATE DATA RULE grandes APPLY n > 20", admin, null);
        ds.execute("CREATE PROCEDURE ins(n) AS $$ db.exec('CREATE doc CONTENT {n: $n}', {'n': n}) $$", admin, null);
        Session baixo = as("ze", "operator", "grandes");
        assertThrows(AxonError.class, () -> ds.execute("CALL ins(5)", baixo, null));
        ds.execute("CALL ins(50)", baixo, null);
        assertEquals(1, ds.execute("SELECT * FROM doc", admin, null).asArray().size());
        // viewer nao chama procedure
        Session viewer = as("v", "viewer", "");
        assertThrows(AxonError.class, () -> ds.execute("CALL ins(60)", viewer, null));
    }

    @Test
    void ddlDentroDeProgramaExigeAdmin() {
        Session admin = as("dono", "admin", "");
        ds.execute("CREATE PROCEDURE esquema() AS $$ db.exec('DEFINE TABLE nova SCHEMALESS') $$", admin, null);
        Session baixo = as("ze", "operator", "");
        AxonError e = error(ds, baixo, "CALL esquema()");
        assertTrue(e.getMessage().contains("admin"), e.getMessage());
        ds.execute("CALL esquema()", admin, null);
        assertTrue(ds.execute("INFO FOR DATABASE", admin, null).asObject().get("tables").asArray().stream()
            .anyMatch(v -> v.asString().equals("nova")));
    }

    // ------------------------------------------------------------------
    // cluster-like: o tipo de controle existe e a ordem de replay
    // ------------------------------------------------------------------

    @Test
    void ordemDeReplayDosNovosTipos() {
        assertTrue(ControlCommand.Kind.EVENT.ordinal() < ControlCommand.Kind.FUNCTION.ordinal());
        assertTrue(ControlCommand.Kind.EVENT.ordinal() < ControlCommand.Kind.PACKAGE.ordinal());
        assertTrue(ControlCommand.Kind.EVENT.ordinal() < ControlCommand.Kind.PROCEDURE.ordinal());
    }

    @SuppressWarnings("unused")
    private static KvBackend unused() {
        return null;
    }
}
