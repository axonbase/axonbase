package com.axonbase.jdbc;

import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** O nome de uma tabela vem do banco: listar colunas não pode executá-lo como comando. */
class JdbcMetadataNameTest {

    @Test
    void nomeDeTabelaMaliciosoNaoViraComandoAoListarColunas() throws Exception {
        try (JdbcTestServer server = new JdbcTestServer();
             Connection conn = server.connect();
             Statement st = conn.createStatement()) {
            st.execute("CREATE alvo:1 SET v = 1");
            try {
                st.execute("CREATE `x; DELETE alvo; SELECT * FROM x`:1 SET v = 1");
            } catch (SQLException recusado) {
                // um servidor que recusa o nome também protege o driver
            }

            conn.getMetaData().getColumns(null, null, null, null).close();

            ResultSet rs = st.executeQuery("SELECT * FROM alvo");
            assertTrue(rs.next(), "o nome da tabela foi executado como comando");
        }
    }
}
