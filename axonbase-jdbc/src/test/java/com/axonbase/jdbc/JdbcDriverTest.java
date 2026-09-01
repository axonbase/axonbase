package com.axonbase.jdbc;
import java.sql.*;
public class JdbcDriverTest {
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
}
