package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.security.AuthCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** O /import roda com a identidade e o teto de papel da requisição, e exige administrador do banco. */
class ImportAuthorizationHttpTest {

    private static final String CRIA_ROOT = "DEFINE USER invasor ON ROOT PASSWORD \"p\";";

    private Datastore ds;
    private AxonServer server;
    private SecurityHttp http;

    @BeforeEach
    void setUp() throws Exception {
        ds = Datastore.memory();
        ds.createDatabase("loja", "ops");
        ds.createDatabase("outro", "base");
        ds.authCatalog().defineUser("editor", AuthCatalog.Scope.DATABASE, "loja", "ops", "pw",
            List.of("editor"), List.of());
        ds.authCatalog().defineUser("chefe", AuthCatalog.Scope.DATABASE, "loja", "ops", "pw",
            List.of("admin"), List.of());
        server = AxonServer.startRandomPort(ds, "secret-testing", "root", "pw", true);
        http = new SecurityHttp(server.port());
    }

    @AfterEach
    void tearDown() throws Exception {
        server.stop();
    }

    private HttpResponse<String> importar(String token, String dump) throws Exception {
        return http.post("/import", dump, SecurityHttp.bearer(token, "loja", "ops"));
    }

    private boolean existeUsuario(String nome) {
        return ds.authCatalog().users().stream().anyMatch(u -> u.name().equals(nome));
    }

    private int linhas(String ns, String db, String tabela) {
        Session s = Session.create();
        s.namespace(ns);
        s.database(db);
        return ds.execute("SELECT * FROM " + tabela, s, null).asArray().size();
    }

    @Test
    void editorNaoImporta() throws Exception {
        HttpResponse<String> r = importar(http.signin("loja", "ops", "editor", "pw"), CRIA_ROOT);

        assertEquals(403, r.statusCode(), r.body());
        assertFalse(existeUsuario("invasor"));
    }

    @Test
    void adminDeBancoNaoCriaUsuarioRootPorImport() throws Exception {
        HttpResponse<String> r = importar(http.signin("loja", "ops", "chefe", "pw"), CRIA_ROOT);

        assertEquals(400, r.statusCode(), r.body());
        assertFalse(existeUsuario("invasor"));
    }

    @Test
    void adminDeBancoNaoEscreveEmOutroBancoPorImport() throws Exception {
        HttpResponse<String> r = importar(http.signin("loja", "ops", "chefe", "pw"),
            "USE outro base; CREATE invadido:1 SET v = 1;");

        assertEquals(400, r.statusCode(), r.body());
        assertEquals(0, linhas("outro", "base", "invadido"));
    }

    @Test
    void tetoDePapelValeNoImport() throws Exception {
        String root = http.rootToken("pw");
        HttpResponse<String> r = http.post("/import", CRIA_ROOT, "Authorization", "Bearer " + root,
            "Axon-Ns", "loja", "Axon-Db", "ops", "Axon-Role-Cap", "operator");

        assertEquals(403, r.statusCode(), r.body());
        assertFalse(existeUsuario("invasor"));
    }

    @Test
    void adminDeBancoImportaDadosDoProprioBanco() throws Exception {
        HttpResponse<String> r = importar(http.signin("loja", "ops", "chefe", "pw"), "CREATE itens:2 SET v = 2;");

        assertEquals(200, r.statusCode(), r.body());
        assertEquals(1, linhas("loja", "ops", "itens"));
    }
}
