package com.axonbase.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** O valor de um parâmetro de texto é sempre dado: nunca fecha a string nem vira comando. */
class JdbcParameterEscapeTest {

    private JdbcTestServer server;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        server = new JdbcTestServer();
        conn = server.connect();
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
        server.close();
    }

    private String gravaELe(String valor) throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("UPSERT texto:1 SET v = ?")) {
            ps.setString(1, valor);
            ps.executeUpdate();
        }
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT v FROM texto:1");
            assertTrue(rs.next());
            return rs.getString("v");
        }
    }

    @Test
    void apostrofoEBarraInvertidaVoltamIguais() throws Exception {
        String valor = "O'Brien \\ C:\\novo \\' fim\\";

        assertEquals(valor, gravaELe(valor));
    }

    @Test
    void valorMaliciosoNaoViraComando() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE alvo:1 SET v = 1");
        }
        String valor = "x\\'; DELETE alvo; --";

        assertEquals(valor, gravaELe(valor));
        try (Statement st = conn.createStatement()) {
            assertTrue(st.executeQuery("SELECT * FROM alvo").next());
        }
    }
}
