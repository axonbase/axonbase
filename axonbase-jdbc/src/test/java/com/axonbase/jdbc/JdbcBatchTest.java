package com.axonbase.jdbc;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Nenhuma operação do driver pode informar sucesso sem ter feito o que foi pedido. */
class JdbcBatchTest {

    private JdbcTestServer server;
    private Connection conn;

    @BeforeEach
    void setUp() throws Exception {
        server = new JdbcTestServer();
        conn = server.connect();
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE lote:0 SET v = 0");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        conn.close();
        server.close();
    }

    private int linhas() throws SQLException {
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT * FROM lote");
            int total = 0;
            while (rs.next()) {
                total++;
            }
            return total;
        }
    }

    @Test
    void loteDeStatementGravaTudoEDevolveAsContagens() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.addBatch("CREATE lote:1 SET v = 1");
            st.addBatch("CREATE lote:2 SET v = 2");

            assertArrayEquals(new int[] {1, 1}, st.executeBatch());
            assertArrayEquals(new int[0], st.executeBatch(), "o lote executado é esvaziado");
        }
        assertEquals(3, linhas());
    }

    @Test
    void loteDePreparedStatementGravaCadaConjuntoDeParametros() throws Exception {
        try (PreparedStatement ps = conn.prepareStatement("CREATE lote CONTENT {v: ?}")) {
            for (int i = 1; i <= 3; i++) {
                ps.setInt(1, i);
                ps.addBatch();
            }

            assertArrayEquals(new int[] {1, 1, 1}, ps.executeBatch());
        }
        assertEquals(4, linhas());
    }

    @Test
    void falhaNoLoteLancaBatchUpdateExceptionComOQueJaPassou() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.addBatch("CREATE lote:1 SET v = 1");
            st.addBatch("CREATE lote:1 SET v = 1");
            st.addBatch("CREATE lote:2 SET v = 2");

            BatchUpdateException erro = assertThrows(BatchUpdateException.class, st::executeBatch);

            assertArrayEquals(new int[] {1}, erro.getUpdateCounts());
        }
        assertEquals(2, linhas());
    }

    @Test
    void consultaNoLoteEhRecusada() throws Exception {
        try (Statement st = conn.createStatement()) {
            st.addBatch("SELECT * FROM lote");

            assertThrows(BatchUpdateException.class, st::executeBatch);
        }
    }

    @Test
    void depoisDoCommitATransacaoSeguinteAindaPodeSerDesfeita() throws Exception {
        conn.setAutoCommit(false);
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE lote:1 SET v = 1");
            conn.commit();
            st.execute("CREATE lote:2 SET v = 2");
            conn.rollback();
        }
        conn.setAutoCommit(true);

        assertEquals(2, linhas());
    }

    @Test
    void deleteInformaQuantosRegistrosApagou() throws Exception {
        try (Statement st = conn.createStatement()) {
            assertEquals(1, st.executeUpdate("DELETE lote WHERE v = 0"));
        }
        assertEquals(0, linhas());
    }

    @Test
    void campoNuloEhInformadoPorWasNull() throws Exception {
        try (Statement st = conn.createStatement()) {
            ResultSet rs = st.executeQuery("SELECT v, falta FROM lote:0");
            assertTrue(rs.next());
            rs.getInt("falta");

            assertTrue(rs.wasNull());
        }
    }

    @Test
    void recursosSemSuporteDaoErroEmVezDeNaoFazerNada() throws Exception {
        try (Statement st = conn.createStatement();
             PreparedStatement ps = conn.prepareStatement("CREATE lote CONTENT {v: ?}")) {
            assertThrows(SQLFeatureNotSupportedException.class, st::cancel);
            assertThrows(SQLFeatureNotSupportedException.class, st::getGeneratedKeys);
            assertThrows(SQLFeatureNotSupportedException.class,
                () -> st.executeUpdate("CREATE lote:9 SET v = 9", Statement.RETURN_GENERATED_KEYS));
            assertThrows(SQLFeatureNotSupportedException.class, () -> ps.setBytes(1, new byte[] {1, 2}));
            assertThrows(SQLFeatureNotSupportedException.class, () -> conn.setCatalog("outro"));
            assertThrows(SQLFeatureNotSupportedException.class, () -> conn.setSchema("outro"));
        }
        assertEquals(1, linhas());
    }
}
