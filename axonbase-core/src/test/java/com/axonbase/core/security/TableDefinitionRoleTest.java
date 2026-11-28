package com.axonbase.core.security;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Apagar tabela, mudar a definição de uma que existe e declarar PERMISSIONS são tarefas do administrador do banco. */
class TableDefinitionRoleTest {

    private static Session local() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    private static Session session(String id, String database, String roles) {
        Session s = local();
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str(id));
        claims.put("scope", AxonValue.str("DATABASE"));
        claims.put("ns", AxonValue.str("app"));
        claims.put("db", AxonValue.str(database));
        claims.put("roles", AxonValue.str(roles));
        s.auth(AxonValue.object(claims));
        return s;
    }

    private static Session editor() {
        return session("editor", "main", "editor");
    }

    private static Session admin() {
        return session("chefe", "main", "admin");
    }

    /** {@code doc} tem PERMISSIONS; {@code livre} não tem. */
    private static Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.execute("DEFINE TABLE doc SCHEMALESS PERMISSIONS FOR select WHERE dono = $auth.id; "
            + "DEFINE FIELD dono ON TABLE doc TYPE string READONLY; "
            + "CREATE INDEX doc_dono ON TABLE doc COLUMNS dono; "
            + "CREATE doc:1 CONTENT {dono: 'outro', at: d\"2026-11-01T10:00:00Z\"}; "
            + "DEFINE TABLE livre SCHEMAFULL; "
            + "DEFINE FIELD codigo ON TABLE livre TYPE string READONLY; "
            + "CREATE INDEX livre_codigo ON TABLE livre COLUMNS codigo UNIQUE", local(), null);
        return ds;
    }

    private static void assertRoleRefusal(Datastore ds, String statement, Session s) {
        AxonError refused = assertThrows(AxonError.class, () -> ds.execute(statement, s, null), statement);
        assertTrue(refused.getMessage().contains("admin"), refused.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "DROP TABLE doc",
        "REMOVE TABLE doc",
        "DROP TABLE livre",
        "DEFINE TABLE doc SCHEMALESS",
        "DEFINE TABLE doc SCHEMALESS PERMISSIONS FOR select WHERE true",
        "CREATE TABLE doc (titulo STRING)",
        "ALTER TABLE doc ADD COLUMN titulo STRING",
        "DEFINE FIELD dono ON TABLE doc TYPE string",
        "DEFINE FIELD publico ON TABLE doc TYPE bool DEFAULT true",
        "CREATE INDEX doc_titulo ON TABLE doc COLUMNS titulo",
        "DROP INDEX doc_dono ON TABLE doc",
        "DEFINE TTL ON TABLE doc FIELD at",
        "DEFINE PARTITION ON TABLE livre FIELD at INTERVAL 1d",
        "DEFINE TABLE livre SCHEMALESS",
        "DEFINE TABLE livre DROP SCHEMAFULL",
        "ALTER TABLE livre DROP COLUMN codigo",
        "DEFINE FIELD codigo ON TABLE livre TYPE string",
        "CREATE INDEX livre_codigo ON TABLE livre COLUMNS codigo",
        "DROP INDEX livre_codigo ON TABLE livre",
        "DEFINE TABLE nova SCHEMALESS PERMISSIONS FOR select WHERE true"})
    void editorNaoApagaNaoMudaENaoDeclaraPermissions(String statement) {
        Datastore ds = ds();

        assertRoleRefusal(ds, statement, editor());

        assertNotNull(ds.findDatabase("app", "main").catalog().table("doc").permissions().select());
        assertEquals(1, ds.execute("SELECT * FROM doc", session("outro", "main", "editor"), null).asArray().size());
        assertEquals(0, ds.execute("SELECT * FROM doc", editor(), null).asArray().size());
        assertNull(ds.findDatabase("app", "main").catalog().table("nova"));
    }

    @Test
    void editorNaoApagaAsPermissionsPorImportacao() {
        Datastore ds = ds();

        assertThrows(RuntimeException.class, () -> ds.importDatabase(editor(), "DEFINE TABLE doc SCHEMALESS;"));

        assertNotNull(ds.findDatabase("app", "main").catalog().table("doc").permissions().select());
    }

    @Test
    void administradorDeOutroBancoNaoApagaTabelaDeste() {
        Datastore ds = ds();

        assertRoleRefusal(ds, "DROP TABLE doc", session("vizinho", "outro", "admin"));

        assertNotNull(ds.findDatabase("app", "main").catalog().table("doc"));
    }

    @Test
    void editorCriaTabelaNovaECompletaADefinicao() {
        Datastore ds = ds();
        String script = "DEFINE TABLE pedido SCHEMAFULL; DEFINE FIELD total ON TABLE pedido TYPE number; "
            + "CREATE INDEX pedido_total ON TABLE pedido COLUMNS total; "
            + "CREATE TABLE item (nome STRING NOT NULL); "
            + "CREATE rascunho:1 CONTENT {v: 1}; "
            + "DEFINE FIELD nota ON TABLE rascunho TYPE string";

        ds.execute(script, editor(), null);
        // repetir o que já vale não muda nada, como fazem os migradores a cada partida
        ds.execute("DEFINE TABLE pedido SCHEMAFULL; DEFINE FIELD total ON TABLE pedido TYPE number; "
            + "CREATE INDEX pedido_total ON TABLE pedido COLUMNS total; "
            + "CREATE TABLE IF NOT EXISTS item (nome STRING NOT NULL)", editor(), null);

        assertNotNull(ds.findDatabase("app", "main").catalog().table("item"));
    }

    @Test
    void administradorDoBancoEModoEmbutidoContinuamPodendo() {
        Datastore ds = ds();

        ds.execute("ALTER TABLE doc ADD COLUMN titulo STRING; DEFINE TABLE doc SCHEMALESS; DROP TABLE doc",
            admin(), null);
        ds.execute("DROP TABLE livre", local(), null);

        assertNull(ds.findDatabase("app", "main").catalog().table("doc"));
        assertNull(ds.findDatabase("app", "main").catalog().table("livre"));
    }

    @Test
    void editorImportaOProprioDumpEmBancoVazio() {
        Datastore source = Datastore.memory();
        source.createDatabase("app", "main");
        source.execute("CREATE TABLE item (nome STRING NOT NULL); CREATE item:1 CONTENT {nome: 'a'}; "
            + "CREATE INDEX item_nome ON TABLE item COLUMNS nome; DEFINE TABLE nota SCHEMAFULL; "
            + "DEFINE FIELD texto ON TABLE nota TYPE string; CREATE nota:1 CONTENT {texto: 'b'}", local(), null);
        Datastore target = Datastore.memory();
        target.createDatabase("app", "main");

        target.importDatabase(editor(), source.exportDatabase("app", "main"));

        assertEquals(1, target.execute("SELECT * FROM item", local(), null).asArray().size());
        assertEquals(1, target.execute("SELECT * FROM nota", local(), null).asArray().size());
    }
}
