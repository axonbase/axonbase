package com.axonbase.jdbc;
import java.sql.*;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JdbcDriverTest {
    @org.junit.jupiter.api.Test
    public void exposesConnectionAndTlsPropertiesToJdbcClients() throws Exception {
        DriverPropertyInfo[] properties = new AxonDriver().getPropertyInfo(
                "jdbc:axonbase:wss://127.0.0.1:8543/rpc/ws?ns=test&db=dev", new java.util.Properties());

        assertEquals(8, properties.length);
        assertTrue(Arrays.stream(properties).anyMatch(property -> property.name.equals("keystore")));
        assertTrue(Arrays.stream(properties).anyMatch(property -> property.name.equals("keystorePassword")));
        assertTrue(Arrays.stream(properties).anyMatch(property -> property.name.equals("truststore")));
        assertTrue(Arrays.stream(properties).anyMatch(property -> property.name.equals("truststorePassword")));
        assertEquals(0, new AxonDriver().getMajorVersion());
        assertEquals(2, new AxonDriver().getMinorVersion());
    }

    @org.junit.jupiter.api.Test
    public void testConnection() throws Exception {
        try {
            Class.forName("com.axonbase.jdbc.AxonDriver");
            Connection conn = DriverManager.getConnection("jdbc:axonbase:ws://127.0.0.1:8006/rpc/ws?ns=test&db=dev");
            Statement stmt = conn.createStatement();
            stmt.execute("CREATE person_jdbtest CONTENT {name: 'Ana', age: 30}");
            ResultSet rs = stmt.executeQuery("SELECT * FROM person_jdbtest");
            int count = 0;
            while (rs.next()) {
                assert "Ana".equals(rs.getString("name"));
                assert 30 == rs.getInt("age");
                count++;
            }
            assert count == 1;
            rs.close(); stmt.close(); conn.close();
        } catch (SQLException e) {
            if (e.getMessage().contains("erro ao conectar")) {
                System.out.println("testConnection skipped: servidor não disponível");
                return;
            }
            throw e;
        }
    }

    @org.junit.jupiter.api.Test
    public void describeReturnsTableDefinition() throws Exception {
        try {
            Class.forName("com.axonbase.jdbc.AxonDriver");
            var properties = new java.util.Properties();
            properties.setProperty("user", "alvaro");
            properties.setProperty("password", "senha123");
            try (Connection conn = DriverManager.getConnection(
                    "jdbc:axonbase:ws://127.0.0.1:8080/rpc/ws?ns=test&db=dev", properties);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("DESCRIBE person")) {
                assertTrue(rs.next());
                assertEquals("person", rs.getString("name"));
                assertEquals("SCHEMALESS", rs.getString("schema"));
                assertEquals("municipio_id", rs.getString("field"));
            }
        } catch (SQLException e) {
            if (e.getMessage().contains("erro ao conectar")) {
                System.out.println("describeReturnsTableDefinition skipped: servidor não disponível");
                return;
            }
            throw e;
        }
    }

}
