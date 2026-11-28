package com.axonbase.core.qos;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static com.axonbase.core.qos.WorkloadClassifier.Decision.ANALYTICAL;
import static com.axonbase.core.qos.WorkloadClassifier.Decision.DEMOTABLE;
import static com.axonbase.core.qos.WorkloadClassifier.Decision.TRANSACTIONAL;
import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("QoS: classificação das consultas")
class WorkloadClassifierTest {

    private static QosSettings settings(Map<String, String> env) {
        return QosSettings.from(env::get, 8, 112);
    }

    private static Session session(String user) {
        Session session = Session.create();
        session.namespace("n");
        session.database("d");
        if (user != null) {
            session.auth(AxonValue.object(Map.of("id", AxonValue.str(user))));
        }
        return session;
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
        "SELECT * FROM item | ANALYTICAL",
        "SELECT count() FROM item GROUP ALL | ANALYTICAL",
        "SELECT cidade, count() FROM item WHERE ativo = true GROUP BY cidade | ANALYTICAL",
        "SELECT * FROM (SELECT * FROM item) | ANALYTICAL",
        "SELECT * FROM item:1 | DEMOTABLE",
        "SELECT * FROM item WHERE nome = 'a' | DEMOTABLE",
        "SELECT * FROM item LIMIT 10 | DEMOTABLE",
        "CREATE item CONTENT { x: 1 } | TRANSACTIONAL",
        "SELECT * FROM item; UPDATE item SET x = 2 | TRANSACTIONAL",
        "DELETE item:1 | TRANSACTIONAL",
        "isto nao e axonql ((( | TRANSACTIONAL"
    })
    @DisplayName("modo automático")
    void automatic(String sql, WorkloadClassifier.Decision expected) {
        assertEquals(expected, WorkloadClassifier.classify(settings(Map.of("AXON_QOS", "auto")), sql,
            session(null), null));
    }

    @Test
    @DisplayName("no modo explícito só o pedido do cliente ou o usuário classificam")
    void explicitMode() {
        QosSettings explicit = settings(Map.of("AXON_QOS", "explicit", "AXON_QOS_ANALYTICAL_USERS", "bi"));

        assertEquals(TRANSACTIONAL,
            WorkloadClassifier.classify(explicit, "SELECT * FROM item", session("app"), null));
        assertEquals(ANALYTICAL,
            WorkloadClassifier.classify(explicit, "SELECT * FROM item:1", session("bi"), null));
        assertEquals(ANALYTICAL,
            WorkloadClassifier.classify(explicit, "SELECT * FROM item:1", session("app"), "Analytical"));
    }

    @Test
    @DisplayName("escrita nunca vira analítica, nem pedida nem de usuário analítico")
    void writesStayTransactional() {
        QosSettings auto = settings(Map.of("AXON_QOS", "auto", "AXON_QOS_ANALYTICAL_USERS", "bi"));

        assertEquals(TRANSACTIONAL,
            WorkloadClassifier.classify(auto, "CREATE item CONTENT { x: 1 }", session("bi"), "analytical"));
    }

    @Test
    @DisplayName("o cliente só se declara transacional quando o operador confia nele")
    void clientCannotEscapeByDefault() {
        QosSettings strict = settings(Map.of("AXON_QOS", "auto"));
        QosSettings trusting = settings(Map.of("AXON_QOS", "auto", "AXON_QOS_TRUST_CLIENT", "true"));

        assertEquals(ANALYTICAL,
            WorkloadClassifier.classify(strict, "SELECT * FROM item", session(null), "transactional"));
        assertEquals(TRANSACTIONAL,
            WorkloadClassifier.classify(trusting, "SELECT * FROM item", session(null), "transactional"));
    }

    @Test
    @DisplayName("dentro de transação explícita tudo é transacional")
    void openTransactionStaysTransactional() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("n", "d");
        Session session = session(null);
        ds.beginSession(session);

        assertEquals(TRANSACTIONAL, WorkloadClassifier.classify(settings(Map.of("AXON_QOS", "auto")),
            "SELECT * FROM item", session, "analytical"));
        ds.cancelSession(session);
    }
}
