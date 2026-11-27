package com.axonbase.core.security;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.pl.ProgramDef;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Criar, substituir ou apagar função, procedure e package é tarefa do administrador do banco: o corpo roda com a
 * identidade de quem chama, inclusive a do root, e uma função pode decidir quem lê uma tabela.
 */
class ProgramDefinitionRoleTest {

    private static final String FUNCTION = "CREATE OR REPLACE FUNCTION regra() AS $$ return False $$";
    private static final String PROCEDURE =
        "CREATE OR REPLACE PROCEDURE limpa() AS $$ db.exec('CREATE marca CONTENT {ok: true}') $$";
    private static final String PACKAGE = "CREATE OR REPLACE PACKAGE util AS $$\ndef um():\n    return 1\n$$";
    private static final String TAKEOVER = "CREATE OR REPLACE PROCEDURE limpa() AS $$ db.exec('CREATE USER intruso "
        + "ON ROOT PASSWORD \"Test-pw-1\" ROLES admin') $$";

    private static Session local() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    private static Session session(String id, String scope, String database, String roles) {
        Session s = local();
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str(id));
        claims.put("scope", AxonValue.str(scope));
        if (!"ROOT".equals(scope)) {
            claims.put("ns", AxonValue.str("app"));
            claims.put("db", AxonValue.str(database));
        }
        claims.put("roles", AxonValue.str(roles));
        s.auth(AxonValue.object(claims));
        return s;
    }

    private static Session root() {
        return session("root", "ROOT", "main", "admin");
    }

    private static Session databaseAdmin() {
        return session("chefe", "DATABASE", "main", "admin");
    }

    private static Session editor() {
        return session("editor", "DATABASE", "main", "editor");
    }

    private static Session neighbourAdmin() {
        return session("vizinho", "DATABASE", "vizinho", "admin");
    }

    private static Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.execute(FUNCTION, databaseAdmin(), null);
        ds.execute(PROCEDURE, databaseAdmin(), null);
        ds.execute(PACKAGE, databaseAdmin(), null);
        return ds;
    }

    private static ProgramDef program(Datastore ds, String name) {
        return ds.programs().catalog("app", "main").get(name);
    }

    private static void assertAdminRequired(Datastore ds, String statement, Session s) {
        AxonError refused = assertThrows(AxonError.class, () -> ds.execute(statement, s, null), statement);
        assertTrue(refused.getMessage().contains("admin"), refused.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "CREATE FUNCTION nova() AS $$ return 1 $$",
        "CREATE OR REPLACE FUNCTION regra() AS $$ return True $$",
        "CREATE PROCEDURE nova() AS $$ pass $$",
        TAKEOVER,
        "CREATE PACKAGE nova AS $$\nK = 1\n$$",
        "CREATE OR REPLACE PACKAGE util AS $$\ndef um():\n    return 2\n$$",
        "DROP FUNCTION regra",
        "DROP PROCEDURE limpa",
        "DROP PACKAGE util"})
    void editorEAdministradorDeOutroBancoSaoRecusados(String statement) {
        Datastore ds = ds();
        Map<String, String> before = new LinkedHashMap<>();
        ds.programs().catalog("app", "main").all().forEach(d -> before.put(d.name(), d.definition()));

        for (Session s : new Session[] {editor(), neighbourAdmin()}) {
            assertAdminRequired(ds, statement, s);
        }

        Map<String, String> after = new LinkedHashMap<>();
        ds.programs().catalog("app", "main").all().forEach(d -> after.put(d.name(), d.definition()));
        assertEquals(before, after);
    }

    @Test
    void editorNaoTrocaAProcedureQueORootChama() {
        Datastore ds = ds();

        assertAdminRequired(ds, TAKEOVER, editor());
        ds.execute("CALL limpa()", root(), null);

        assertNull(ds.authCatalog().user("intruso", null, null));
        assertEquals(1, ds.execute("SELECT * FROM marca", local(), null).asArray().size());
    }

    @Test
    void editorNaoAbreATabelaTrocandoAFuncaoDaPermissao() {
        Datastore ds = ds();
        ds.execute("DEFINE TABLE cofre SCHEMALESS PERMISSIONS FOR select WHERE regra()", databaseAdmin(), null);
        ds.execute("CREATE cofre:1 CONTENT {v: 'segredo'}", local(), null);

        assertAdminRequired(ds, "CREATE OR REPLACE FUNCTION regra() AS $$ return True $$", editor());

        assertEquals(0, ds.execute("SELECT * FROM cofre", editor(), null).asArray().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {FUNCTION, PROCEDURE, PACKAGE})
    void repetirADefinicaoEmVigorNaoPedePapelNemMudaNada(String statement) {
        Datastore ds = ds();
        Map<String, String> before = new LinkedHashMap<>();
        ds.programs().catalog("app", "main").all().forEach(d -> before.put(d.name(), d.definition()));

        assertDoesNotThrow(() -> ds.execute(statement, editor(), null));

        // versão, datas e dono ficam no cabeçalho da definição: nada disso passa para quem repetiu
        Map<String, String> after = new LinkedHashMap<>();
        ds.programs().catalog("app", "main").all().forEach(d -> after.put(d.name(), d.definition()));
        assertEquals(before, after);
    }

    @Test
    void administradorDoBancoERootContinuamPodendo() {
        Datastore ds = ds();

        for (Session s : new Session[] {databaseAdmin(), root(), local()}) {
            ds.execute("DROP FUNCTION regra; DROP PROCEDURE limpa; DROP PACKAGE util", s, null);
            ds.execute(FUNCTION, s, null);
            ds.execute(PROCEDURE, s, null);
            ds.execute(PACKAGE, s, null);
        }

        assertNotNull(program(ds, "regra"));
        assertNotNull(program(ds, "limpa"));
        assertNotNull(program(ds, "util"));
    }

    @Test
    void chamarContinuaSemExigirPapelDeAdministrador() {
        Datastore ds = ds();

        ds.execute("CALL limpa()", editor(), null);

        assertEquals(1, ds.execute("RETURN util::um()", editor(), null).asLong());
        assertEquals(1, ds.execute("SELECT * FROM marca", local(), null).asArray().size());
    }

    @Test
    void repetirUmDefinerContinuaExigindoPapelDeAdministradorDoBancoParaMudarODono() {
        Datastore ds = ds();
        String definer = "CREATE OR REPLACE FUNCTION fixa() SECURITY DEFINER AS $$ return 1 $$";
        ds.execute(definer, databaseAdmin(), null);
        String before = program(ds, "fixa").definition();

        assertAdminRequired(ds, definer, editor());
        ds.execute(definer, neighbourAdmin(), null);

        assertEquals(before, program(ds, "fixa").definition());
    }

    @Test
    void importacaoSegueAMesmaRegra() {
        Datastore source = ds();
        String dump = source.exportDatabase("app", "main");
        Datastore target = Datastore.memory();
        target.createDatabase("app", "main");

        assertAdminRequired(target, FUNCTION, editor());
        assertThrows(AxonError.class, () -> target.importDatabase(editor(), dump));
        assertNull(program(target, "regra"));

        target.importDatabase(databaseAdmin(), dump);

        assertNotNull(program(target, "regra"));
        assertNotNull(program(target, "limpa"));
        assertNotNull(program(target, "util"));
    }
}
