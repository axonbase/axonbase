package com.axonbase.core.security;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Registra o comportamento atual de DEFINE EVENT, que não exige admin e cujo corpo roda na sessão de quem disparou.
 * O teste passa enquanto um editor conseguir fazer o root executar o que escreveu no evento. A mudança de papéis
 * depende de decisão do dono do projeto: quando ela vier, a última asserção deve ser invertida.
 */
class EventPrivilegeTest {

    private static Session session(String id, String scope, String roles) {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str(id));
        claims.put("scope", AxonValue.str(scope));
        if (!"ROOT".equals(scope)) {
            claims.put("ns", AxonValue.str("app"));
            claims.put("db", AxonValue.str("main"));
        }
        claims.put("roles", AxonValue.str(roles));
        s.auth(AxonValue.object(claims));
        return s;
    }

    @Test
    void editorFazORootExecutarOCorpoDoEvento() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        String create = "CREATE USER intruso ON ROOT PASSWORD 'Test-pw-1' ROLES admin";
        Session editor = session("editor", "DATABASE", "editor");

        // direto, o editor não consegue
        assertThrows(RuntimeException.class, () -> ds.execute(create, editor, null));

        // mas consegue definir o evento
        ds.execute("DEFINE EVENT armadilha ON TABLE pedido WHEN $event = 'CREATE' THEN (" + create + ")",
            editor, null);
        assertNull(ds.authCatalog().user("intruso", null, null));

        // e o root, ao gravar na tabela, executa o corpo com o próprio poder
        ds.execute("CREATE pedido:1 CONTENT {v: 1}", session("root", "ROOT", "admin"), null);
        assertNotNull(ds.authCatalog().user("intruso", null, null));
    }
}
