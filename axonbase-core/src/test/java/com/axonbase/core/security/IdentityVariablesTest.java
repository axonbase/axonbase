package com.axonbase.core.security;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.engine.LiveBus;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** $auth e $token vêm dos claims da sessão e o cliente autenticado não os define. */
class IdentityVariablesTest {

    private static Session anonymous() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    private static Session user(String id, String dataRules) {
        Session s = anonymous();
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str(id));
        claims.put("scope", AxonValue.str("DATABASE"));
        claims.put("ns", AxonValue.str("app"));
        claims.put("db", AxonValue.str("main"));
        claims.put("roles", AxonValue.str("editor"));
        if (dataRules != null) {
            claims.put("data_rules", AxonValue.str(dataRules));
        }
        s.auth(AxonValue.object(claims));
        return s;
    }

    private static Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        return ds;
    }

    private static AxonValue forged() {
        return AxonValue.object(Map.of("id", AxonValue.str("outro")));
    }

    @Test
    void authETokenRefletemOsClaims() {
        Datastore ds = ds();
        assertEquals("w", ds.execute("RETURN $auth.id", user("w", null), null).asString());
        assertEquals("DATABASE", ds.execute("RETURN $token.scope", user("w", null), null).asString());
    }

    @Test
    void sessaoAutenticadaNaoDefineAuthNemTokenPorLet() {
        Datastore ds = ds();
        assertThrows(AxonError.class, () -> ds.execute("LET $auth = {id: 'outro'}", user("w", null), null));
        assertThrows(AxonError.class, () -> ds.execute("LET $token = {scope: 'ROOT'}", user("w", null), null));
    }

    @Test
    void sessaoAutenticadaNaoDefineAuthPorVariavelDaRequisicao() {
        Datastore ds = ds();
        assertThrows(AxonError.class,
            () -> ds.execute("RETURN 1", user("w", null), Map.of("auth", forged())));
        assertThrows(AxonError.class,
            () -> ds.execute("RETURN 1", user("w", null), Map.of("token", forged())));
    }

    @Test
    void variavelDefinidaAntesDaAutenticacaoEIgnorada() {
        Datastore ds = ds();
        Session s = user("w", null);
        s.vars().set("auth", forged());
        assertEquals("w", ds.execute("RETURN $auth.id", s, null).asString());
    }

    @Test
    void modoEmbutidoSemAuthContinuaDefinindoAsVariaveis() {
        Datastore ds = ds();
        Session s = anonymous();
        assertDoesNotThrow(() -> ds.execute("LET $auth = {id: 'embutido'}", s, null));
        assertEquals("embutido", ds.execute("RETURN $auth.id", s, null).asString());
    }

    @Test
    void regraComAuthIdFiltraEInjetaOValorDoUsuario() {
        Datastore ds = ds();
        ds.execute("CREATE DATA RULE eu APPLY dono = $auth.id", anonymous(), null);
        ds.execute("CREATE t:1 CONTENT {dono: 'outro'}", anonymous(), null);
        ds.execute("CREATE t:2 CONTENT {dono: 'w'}", anonymous(), null);

        assertEquals(1, ds.execute("SELECT * FROM t", user("w", "eu"), null).asArray().size());
        ds.execute("CREATE t:3 CONTENT {dono: 'outro'}", user("w", "eu"), null);
        assertEquals("w", ds.execute("RETURN t:3.dono", anonymous(), null).asString());
        assertEquals(2, ds.execute("SELECT * FROM t", user("w", "eu"), null).asArray().size());
    }

    @Test
    void regraSemOValorDoClaimNegaTudo() {
        Datastore ds = ds();
        ds.execute("CREATE DATA RULE inquilino APPLY dono = $auth.tenant", anonymous(), null);
        ds.execute("CREATE t:1 CONTENT {x: 1}", anonymous(), null);

        assertEquals(0, ds.execute("SELECT * FROM t", user("w", "inquilino"), null).asArray().size());
        assertEquals(0, ds.execute("SELECT * FROM t LIMIT 10", user("w", "inquilino"), null).asArray().size());
        assertEquals(0, ds.execute("UPDATE t SET x = 2", user("w", "inquilino"), null).asArray().size());
        assertThrows(AxonError.class,
            () -> ds.execute("CREATE t:2 CONTENT {x: 3}", user("w", "inquilino"), null));
    }

    @Test
    void liveAvaliaAuthDoAssinanteENaoDeQuemEscreve() {
        Datastore ds = ds();
        ds.execute("DEFINE TABLE t SCHEMALESS", anonymous(), null);
        Session subscriber = user("w", null);
        List<LiveBus.Notification> received = new ArrayList<>();
        subscriber.liveListener(received::add);
        ds.execute("LIVE SELECT * FROM t WHERE dono = $auth.id", subscriber, null);

        ds.execute("CREATE t:1 CONTENT {dono: 'z'}", user("z", null), null);
        assertEquals(0, received.size());
        ds.execute("CREATE t:2 CONTENT {dono: 'w'}", user("z", null), null);
        assertEquals(1, received.size());
    }
}
