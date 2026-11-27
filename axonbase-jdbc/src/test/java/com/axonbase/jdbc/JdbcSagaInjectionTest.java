package com.axonbase.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Nome de SAGA e correlação são dados: nunca fecham o texto nem viram comando. */
class JdbcSagaInjectionTest {

    private JdbcTestServer server;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        server = new JdbcTestServer();
        conn = server.connect();
        executa("CREATE alvo:1 SET v = 1");
        executa("BEGIN SAGA z WITH CORRELATION 'x_'");
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
        server.close();
    }

    private void executa(String comando) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(comando);
        }
    }

    private boolean alvoExiste() throws SQLException {
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT * FROM alvo");
            return rs.next();
        }
    }

    private boolean entra(String saga, String correlacao) throws SQLException {
        try (SagaBinding ignored = conn.unwrap(SagaScope.class).joinSaga(saga, correlacao)) {
            return true;
        } catch (SQLException recusado) {
            return false;
        }
    }

    @Test
    void correlacaoMaliciosaNaoViraComando() throws Exception {
        boolean entrou = entra("z", "x\\'; DELETE alvo; --");

        assertTrue(alvoExiste(), "a correlação foi executada como comando");
        assertTrue(!entrou, "não existe SAGA com essa correlação");
    }

    @Test
    void nomeMaliciosoNaoViraComando() throws Exception {
        boolean entrou = entra("z WITH CORRELATION 'x_'; DELETE alvo; --", "x_");

        assertTrue(alvoExiste(), "o nome da SAGA foi executado como comando");
        assertTrue(!entrou, "não existe SAGA com esse nome");
    }

    @Test
    void nomeECorrelacaoComAspaEBarraEntramNaSaga() throws Exception {
        executa("BEGIN SAGA 'pedido d\\'agua' WITH CORRELATION 'O\\'Brien \\\\ 1'");

        assertDoesNotThrow(() -> conn.unwrap(SagaScope.class)
            .joinSaga("pedido d'agua", "O'Brien \\ 1").close());
    }
}
