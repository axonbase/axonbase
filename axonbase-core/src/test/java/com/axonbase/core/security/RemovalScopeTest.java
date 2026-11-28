package com.axonbase.core.security;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.FileBackend;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A regra de escopo vale também para remover identidades, acessos, links e sagas. */
class RemovalScopeTest {

    private static Session as(String scope, String ns, String db) {
        Session s = anonymous();
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str("tester"));
        claims.put("scope", AxonValue.str(scope));
        if (ns != null) claims.put("ns", AxonValue.str(ns));
        if (db != null) claims.put("db", AxonValue.str(db));
        claims.put("roles", AxonValue.str("admin"));
        s.auth(AxonValue.object(claims));
        return s;
    }

    private static Session dbAdmin() {
        return as("DATABASE", "app", "main");
    }

    private static Session nsAdmin() {
        return as("NAMESPACE", "app", null);
    }

    private static Session rootAdmin() {
        return as("ROOT", null, null);
    }

    private static Session anonymous() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    private static Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.authCatalog().ensureUser("root", "Test-pw-1");
        return ds;
    }

    @Test
    void adminDeBancoNaoApagaORoot() {
        Datastore ds = ds();
        assertThrows(AxonError.class, () -> ds.execute("DROP USER root", dbAdmin(), null));
        assertNotNull(ds.authCatalog().verify("root", "Test-pw-1", null, null));
    }

    @Test
    void adminDeNamespaceNaoApagaOutroRoot() {
        Datastore ds = ds();
        ds.authCatalog().defineUser("chefe", AuthCatalog.Scope.ROOT, null, null, "Test-pw-1", List.of("admin"),
            List.of());
        assertThrows(AxonError.class, () -> ds.execute("DROP USER chefe", nsAdmin(), null));
        assertNotNull(ds.authCatalog().verify("chefe", "Test-pw-1", null, null));
    }

    @Test
    void adminDeBancoNaoApagaUsuarioDeNamespace() {
        Datastore ds = ds();
        ds.execute("CREATE USER gestor ON NAMESPACE PASSWORD 'Test-pw-1'", anonymous(), null);
        assertThrows(AxonError.class, () -> ds.execute("DROP USER gestor", dbAdmin(), null));
        assertNotNull(ds.authCatalog().user("gestor", "app", "main"));
    }

    @Test
    void adminDeBancoApagaUsuarioDoProprioBanco() {
        Datastore ds = ds();
        ds.execute("CREATE USER comum ON DATABASE PASSWORD 'Test-pw-1'", anonymous(), null);
        assertDoesNotThrow(() -> ds.execute("DROP USER comum", dbAdmin(), null));
        assertNull(ds.authCatalog().user("comum", "app", "main"));
    }

    @Test
    void rootInicialNaoPodeSerApagadoNemPeloProprioRoot() {
        Datastore ds = ds();
        assertThrows(AxonError.class, () -> ds.execute("DROP USER root", rootAdmin(), null));
        assertNotNull(ds.authCatalog().verify("root", "Test-pw-1", null, null));
    }

    @Test
    void rootInicialNaoPodeSerApagadoPelaApiDeGestao() {
        Datastore ds = ds();
        assertThrows(AxonError.class, () -> ds.dropUser(null, "root", AuthCatalog.Scope.ROOT, null, null));
        assertNotNull(ds.authCatalog().verify("root", "Test-pw-1", null, null));
    }

    @Test
    void sessaoSemAutenticacaoNaoApagaORootInicialENaoFalha() {
        Datastore ds = ds();
        assertDoesNotThrow(() -> ds.execute("DROP USER root", anonymous(), null));
        assertNotNull(ds.authCatalog().verify("root", "Test-pw-1", null, null));
    }

    @Test
    void reinicioReaplicaDropUserAntigoDoRootSemFalhar(@TempDir Path dir) {
        // catálogo gravado por uma versão anterior, em que o DROP do root era aceito
        String path = dir.resolve("control.db").toString();
        Datastore first = new Datastore(new FileBackend(path));
        first.createDatabase("app", "main");
        first.execute("CREATE USER root ON ROOT PASSWORD 'Test-pw-1' ROLES admin", anonymous(), null);
        first.execute("DROP USER root", anonymous(), null);

        Datastore recovered = assertDoesNotThrow(() -> new Datastore(new FileBackend(path)));
        recovered.authCatalog().ensureUser("root", "Test-pw-1");
        assertNotNull(recovered.authCatalog().verify("root", "Test-pw-1", null, null));
    }

    @Test
    void adminDeBancoNaoDefineNemRevogaAcessoDeEscopoMaior() {
        Datastore ds = ds();
        ds.execute("DEFINE ACCESS amplo ON NAMESPACE", anonymous(), null);
        assertThrows(AxonError.class, () -> ds.execute("DEFINE ACCESS outro ON NAMESPACE", dbAdmin(), null));
        assertThrows(AxonError.class, () -> ds.execute("DEFINE ACCESS outro ON ROOT", dbAdmin(), null));
        assertThrows(AxonError.class, () -> ds.execute("REVOKE ACCESS amplo", dbAdmin(), null));
        assertNotNull(ds.authCatalog().access("amplo", "app", "main"));
        assertDoesNotThrow(() -> ds.execute("DEFINE ACCESS interno ON DATABASE", dbAdmin(), null));
        assertDoesNotThrow(() -> ds.execute("REVOKE ACCESS interno", dbAdmin(), null));
        assertDoesNotThrow(() -> ds.execute("REVOKE ACCESS amplo", nsAdmin(), null));
    }

    @Test
    void linkDeBancoExigeEscopoDeNamespace() {
        Datastore ds = ds();
        String define = "DEFINE DATABASE LINK \"remoto\" CONNECT BY \"http://127.0.0.1:1\"";
        ds.defineDatabaseLink("app", "existente",
            new Datastore.DatabaseLinkDef("existente", "http://127.0.0.1:1", "app", "x", "u", "Test-pw-1"));
        assertThrows(AxonError.class, () -> ds.execute(define, dbAdmin(), null));
        assertThrows(AxonError.class, () -> ds.execute("DROP DATABASE LINK \"existente\"", dbAdmin(), null));
        assertNotNull(ds.databaseLink("app", "existente"));
        assertDoesNotThrow(() -> ds.execute("DROP DATABASE LINK \"existente\"", nsAdmin(), null));
        assertNull(ds.databaseLink("app", "existente"));
    }

    @Test
    void sagaExigeEscopoRoot() {
        Datastore ds = ds();
        ds.defineSaga("pedido", new Datastore.SagaDef("pedido", List.of("remoto")));
        assertThrows(AxonError.class, () -> ds.execute("DROP SAGA pedido", dbAdmin(), null));
        assertThrows(AxonError.class, () -> ds.execute("DROP SAGA pedido", nsAdmin(), null));
        assertThrows(AxonError.class,
            () -> ds.execute("CREATE SAGA pedido WITH DATABASES 'outro'", dbAdmin(), null));
        assertNotNull(ds.saga("pedido"));
        assertDoesNotThrow(() -> ds.execute("DROP SAGA pedido", rootAdmin(), null));
        assertNull(ds.saga("pedido"));
    }
}
