package com.axonbase.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Um número negativo depois de um sinal de menos não pode abrir um comentário {@code --}. */
class JdbcNegativeParameterTest {

    private JdbcTestServer server;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        server = new JdbcTestServer();
        conn = server.connect();
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE alvo:1 SET v = 1");
            st.execute("CREATE conta:1 SET a = 1, b = 'x'");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
        server.close();
    }

    private boolean alvoExiste() throws SQLException {
        try (Statement st = conn.createStatement()) {
            return st.executeQuery("SELECT * FROM alvo").next();
        }
    }

    @Test
    void textoSeguinteNaoViraComando() throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM conta WHERE a = -? AND b = ?")) {
            ps.setInt(1, -1);
            ps.setString(2, "\n0; DELETE alvo; --");
            ResultSet rs = ps.executeQuery();

            assertFalse(rs.next());
        } catch (SQLException recusado) {
            // o que importa é o que o servidor executou
        }

        assertTrue(alvoExiste(), "o parâmetro de texto foi executado como comando");
    }

    @Test
    void menosComNumeroNegativoContinuaSendoUmaConta() throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM conta WHERE a = -? AND b = ?")) {
            ps.setInt(1, -1);
            ps.setString(2, "x");
            ResultSet rs = ps.executeQuery();

            assertTrue(rs.next());
            assertEquals("x", rs.getString("b"));
        }
    }

    @Test
    void textoComQuebraDeLinhaEmComentarioDoModeloNaoSaiDoComentario() throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("SELECT * FROM conta -- filtro antigo: ?\n")) {
            ps.setString(1, "\n; DELETE alvo; --");
            ps.executeQuery();
        }

        assertTrue(alvoExiste(), "o valor saiu do comentário");
    }
}
