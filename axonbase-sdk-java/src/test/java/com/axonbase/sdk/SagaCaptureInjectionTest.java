package com.axonbase.sdk;

import com.axonbase.core.engine.Datastore;
import com.axonbase.server.AxonServer;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tabela, chave e estado anterior capturados pela SAGA são dados: nunca viram comando. */
class SagaCaptureInjectionTest {

    private static final String NS = "test";
    private static final String DB = "saga_test";

    private AxonServer server;
    private Axon axon;
    private SagaTransaction saga;

    @BeforeEach
    void setUp() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase(NS, DB);
        server = AxonServer.startRandomPort(ds, "secret-testing");
        axon = Axon.connect("ws://127.0.0.1:" + server.port() + "/rpc/ws");
        axon.use(NS, DB);
        axon.query("CREATE inventory:sku1 SET qty = 10");
        axon.query("CREATE inventory:sku2 SET qty = 1");
    }

    /** Depois dos dados do teste: com a SAGA ativa o servidor registraria um passo para cada um deles. */
    private void inicia() {
        saga = axon.saga("pedido", "corr-cap");
        saga.begin();
    }

    @AfterEach
    void tearDown() throws Exception {
        axon.close();
        server.stop();
    }

    private int registros() {
        axon.use(NS, DB);
        return axon.query("SELECT * FROM inventory").asArray().size();
    }

    private void capturaAceitaOuRecusada(String table, String recordId) {
        try {
            saga.capture(table, recordId, NS, DB, "UPDATE");
        } catch (RuntimeException recusada) {
            // o que importa é o que o servidor executou
        }
    }

    private Map<String, AxonValue> passoGravado() {
        axon.use("system", "saga");
        AxonValue steps = axon.query(
            "SELECT * FROM saga_step WHERE correlation_id = 'corr-cap' AND table_name = 'inventory'");
        assertEquals(1, steps.asArray().size());
        return steps.asArray().get(0).asObject();
    }

    @Test
    void chaveMaliciosaNaoViraComando() {
        inicia();

        capturaAceitaOuRecusada("inventory", "sku1; DELETE inventory");

        assertEquals(2, registros());
    }

    @Test
    void tabelaForaDoFormatoDeIdentificadorERecusada() {
        inicia();

        assertThrows(IllegalArgumentException.class, () -> saga.capture(
            "inventory:sku1; DELETE inventory; SELECT * FROM inventory", "sku1", NS, DB, "UPDATE"));

        assertEquals(2, registros());
    }

    @Test
    void chaveComAspaEHifenECapturada() {
        axon.query("CREATE inventory:'sku\\'1-a' SET qty = 3");
        inicia();

        saga.capture("inventory", "sku'1-a", NS, DB, "UPDATE");

        Map<String, AxonValue> passo = passoGravado();
        assertEquals("sku'1-a", passo.get("record_key").asString());
        assertEquals(3, passo.get("before").asObject().get("qty").asLong());
    }

    @Test
    void capturaGravaUmUnicoPassoEMantemACorrelacaoDaSessao() {
        inicia();

        saga.capture("inventory", "sku1", NS, DB, "UPDATE");

        assertEquals("corr-cap", axon.query("RETURN $saga_corr").asString());
        axon.use("system", "saga");
        assertEquals(1, axon.query("SELECT * FROM saga_step WHERE correlation_id = 'corr-cap'")
            .asArray().size());
    }

    @Test
    void chaveMaliciosaDentroDoEstadoAnteriorNaoViraComando() {
        String chave = "x\": 1}}, k: 0}; DELETE inventory; CREATE lixo CONTENT {a: {b: {\"y";
        axon.query("CREATE inventory:mal CONTENT $doc",
            Map.of("doc", AxonValue.object(Map.of("zz", AxonValue.object(Map.of(chave, AxonValue.num(1)))))));
        inicia();

        capturaAceitaOuRecusada("inventory", "mal");

        assertEquals(3, registros());
        Map<String, AxonValue> anterior = passoGravado().get("before").asObject();
        assertEquals(1, anterior.get("zz").asObject().get(chave).asLong());
    }
}
