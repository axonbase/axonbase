package com.axonbase.jdbc;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * Teste real do driver JDBC contra servidor rodando.
 * Operações: CREATE, INSERT, SELECT, UPDATE, DELETE, transações, savepoints.
 */
public class JdbcFullTest {
    static int passed = 0;
    static int failed = 0;

    public static void main(String[] args) throws Exception {
        Class.forName("com.axonbase.jdbc.AxonDriver");
        String url = "jdbc:axonbase:ws://127.0.0.1:8007/rpc/ws?ns=test&db=dev";
        System.out.println("Conectando a " + url);

        try (Connection conn = DriverManager.getConnection(url)) {
            test("Conexão estabelecida", true);

            // 1. CREATE TABLE (na verdade CREATE tabela:chave no AxonQL)
            test("CREATE", () -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE person CONTENT {name: 'Ana', age: 30, city: 'SP'}");
                    stmt.execute("CREATE person CONTENT {name: 'Bob', age: 25, city: 'RJ'}");
                    stmt.execute("CREATE person CONTENT {name: 'Eva', age: 35, city: 'SP'}");
                }
            });

            // 2. SELECT
            test("SELECT *", () -> {
                try (Statement stmt = conn.createStatement()) {
                    ResultSet rs = stmt.executeQuery("SELECT * FROM person ORDER BY name");
                    List<String> names = new ArrayList<>();
                    while (rs.next()) {
                        names.add(rs.getString("name"));
                    }
                    assert names.size() == 3 : "Esperava 3 registros, tem " + names.size();
                    assert names.get(0).equals("Ana") : "Primeiro deveria ser Ana, era " + names.get(0);
                    assert names.get(1).equals("Bob");
                    assert names.get(2).equals("Eva");
                }
            });

            // 3. SELECT com WHERE
            test("SELECT WHERE", () -> {
                try (Statement stmt = conn.createStatement()) {
                    ResultSet rs = stmt.executeQuery("SELECT VALUE name FROM person WHERE age > 30");
                    int count = 0;
                    while (rs.next()) { count++; }
                    assert count == 1 : "age>30 devia ter 1, tem " + count;
                }
            });

            // 4. UPDATE
            test("UPDATE", () -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("UPDATE person SET age = 31 WHERE name = 'Bob'");
                    ResultSet rs = stmt.executeQuery("SELECT VALUE age FROM person WHERE name = 'Bob'");
                    rs.next();
                    int age = rs.getInt(1);
                    assert age == 31 : "Bob age devia ser 31, era " + age;
                }
            });

            // 5. DELETE
            test("DELETE", () -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("DELETE person WHERE name = 'Eva'");
                    ResultSet rs = stmt.executeQuery("SELECT count() FROM person");
                    rs.next();
                    assert rs.getInt(1) == 2 : "Devia ter 2 registros, tem " + rs.getInt(1);
                }
            });

            // 6. PreparedStatement
            test("PreparedStatement", () -> {
                PreparedStatement pstmt = conn.prepareStatement("CREATE person CONTENT {name: ?, age: ?}");
                pstmt.setString(1, "Charlie");
                pstmt.setInt(2, 28);
                pstmt.execute();
                pstmt.close();
                try (Statement stmt = conn.createStatement()) {
                    ResultSet rs = stmt.executeQuery("SELECT * FROM person WHERE name = 'Charlie'");
                    assert rs.next() : "Charlie nao encontrado";
                    assert rs.getInt("age") == 28 : "Charlie age devia ser 28";
                }
            });

            // 7. Transação COMMIT
            test("Transação COMMIT", () -> {
                conn.setAutoCommit(false);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE person CONTENT {name: 'Tx1', age: 99}");
                }
                conn.commit();
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    ResultSet rs = stmt.executeQuery("SELECT * FROM person WHERE name = 'Tx1'");
                    assert rs.next() : "Tx1 nao encontrada apos commit";
                }
            });

            // 8. Transação ROLLBACK
            test("Transação ROLLBACK", () -> {
                conn.setAutoCommit(false);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE person CONTENT {name: 'RollbackMe', age: 1}");
                }
                conn.rollback();
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    ResultSet rs = stmt.executeQuery("SELECT * FROM person WHERE name = 'RollbackMe'");
                    assert !rs.next() : "RollbackMe apareceu apos rollback";
                }
            });

            // 9. Savepoint e rollback parcial
            test("Savepoint", () -> {
                conn.setAutoCommit(false);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE person CONTENT {name: 'BeforeSP', age: 1}");
                }
                Savepoint sp = conn.setSavepoint("meu_sp");
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("CREATE person CONTENT {name: 'AfterSP', age: 2}");
                }
                conn.rollback(sp);
                conn.commit();
                conn.setAutoCommit(true);
                try (Statement stmt = conn.createStatement()) {
                    ResultSet rs = stmt.executeQuery("SELECT * FROM person WHERE name = 'BeforeSP'");
                    assert rs.next() : "BeforeSP nao encontrada";
                    rs = stmt.executeQuery("SELECT * FROM person WHERE name = 'AfterSP'");
                    assert !rs.next() : "AfterSP nao deveria existir";
                }
            });

            // 10. Key-Value via SQL
            test("Key-Value via SQL", () -> {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("SELECT kv::set('test', 'dev', 'jdbc_key', 'jdbc_value')");
                    ResultSet rs = stmt.executeQuery("SELECT kv::get('test', 'dev', 'jdbc_key')");
                    rs.next();
                    String val = rs.getString(1);
                    assert "jdbc_value".equals(val) : "KV devia ser jdbc_value, era " + val;
                }
            });

            // 11. Metadados
            test("DatabaseMetaData", () -> {
                DatabaseMetaData meta = conn.getMetaData();
                String product = meta.getDatabaseProductName();
                assert "AxonBase".equals(product) : "Product name devia ser AxonBase, era " + product;
                ResultSet tables = meta.getTables(null, null, "%", null);
                boolean found = false;
                while (tables.next()) {
                    if ("person".equalsIgnoreCase(tables.getString("TABLE_NAME"))) found = true;
                }
                tables.close();
                assert found : "Tabela person nao encontrada nos metadados";
            });

            // 12. Scrollable ResultSet
            test("Scrollable ResultSet", () -> {
                try (Statement stmt = conn.createStatement()) {
                    ResultSet rs = stmt.executeQuery("SELECT * FROM person ORDER BY name");
                    rs.last();
                    int last = rs.getRow();
                    assert last >= 2 : "Ultima linha: " + last;
                    rs.first();
                    assert rs.getRow() == 1;
                    rs.absolute(2);
                    assert rs.getRow() == 2;
                    rs.previous();
                    assert rs.getRow() == 1;
                }
            });

        } catch (Exception e) {
            System.err.println("Erro fatal: " + e.getMessage());
            e.printStackTrace();
            failed++;
        }

        System.out.println();
        System.out.println("=== RESULTADO ===");
        System.out.println("Passou: " + passed);
        System.out.println("Falhou: " + failed);
        System.exit(failed > 0 ? 1 : 0);
    }

    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    static void test(String name, ThrowingRunnable block) {
        try {
            block.run();
            passed++;
            System.out.println("  ✅ " + name);
        } catch (Throwable e) {
            failed++;
            String msg = e.getMessage();
            if (msg == null || msg.isBlank()) msg = e.getClass().getSimpleName();
            System.out.println("  ❌ " + name + ": " + msg);
        }
    }

    static void test(String name, boolean condition) {
        if (condition) { passed++; System.out.println("  ✅ " + name); }
        else { failed++; System.out.println("  ❌ " + name); }
    }
}