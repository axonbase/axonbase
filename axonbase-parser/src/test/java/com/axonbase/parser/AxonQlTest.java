package com.axonbase.parser;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Query;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("AxonQL: parsing e round-trip")
class AxonQlTest {

    private static void assertRoundTrip(String sql) {
        Query q = AxonQl.parse(sql);
        String rendered = AxonQl.render(q);
        assertEquals(sql, rendered, "round-trip deve preservar o texto");
    }

    @Test
    void roundTripCreate() {
        assertRoundTrip("CREATE person CONTENT {name: \"Ana\", age: 30}");
    }

    @Test
    void roundTripSelectSimple() {
        assertRoundTrip("SELECT * FROM person");
    }

    @Test
    void roundTripSelectTimeTravel() {
        assertEquals("SELECT * FROM person AT (TIMESTAMP => \"2024-01-01T00:00:00Z\")",
            AxonQl.render(AxonQl.parse("SELECT * FROM person AT (TIMESTAMP => '2024-01-01T00:00:00Z')")));
        assertRoundTrip("SELECT * FROM person AT (COMMIT => 123)");
        assertEquals("SELECT * FROM person BEFORE (STATEMENT => \"01HZY3Q7KX4M6NP8R2TW5V9ABC\")",
            AxonQl.render(AxonQl.parse("SELECT * FROM person BEFORE (STATEMENT => '01HZY3Q7KX4M6NP8R2TW5V9ABC')")));
    }

    @Test
    void selectGuardaTimeTravel() {
        Query q = AxonQl.parse("SELECT * FROM person BEFORE (STATEMENT => '01HZY3Q7KX4M6NP8R2TW5V9ABC')");
        Statement.Select sel = assertInstanceOf(Statement.Select.class, q.statements().get(0));
        Statement.TimeTravel travel = sel.timeTravel();

        assertEquals(Statement.TimeTravelMode.BEFORE, travel.mode());
        assertEquals(Statement.TimeTravelSelector.STATEMENT, travel.selector());
        assertEquals("\"01HZY3Q7KX4M6NP8R2TW5V9ABC\"", Render.expr(travel.value()));
    }

    @Test
    void roundTripSelectWhere() {
        assertRoundTrip("SELECT * FROM person WHERE age > 18");
    }

    @Test
    void roundTripDefineTable() {
        assertRoundTrip("DEFINE TABLE user SCHEMAFULL");
    }

    @Test
    void parseCreateTableSqlFirst() {
        String sql = "CREATE TABLE person ("
            + "id VARCHAR(64) PRIMARY KEY, "
            + "name VARCHAR(255) NOT NULL, "
            + "age INT DEFAULT 18 CHECK (age >= 0), "
            + "location GEOMETRY(POINT), "
            + "embedding VECTOR(1536), "
            + "created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP"
            + ") WITH (SCHEMA = 'FULL')";
        Query q = AxonQl.parse(sql);
        assertEquals(1, q.statements().size());
        Statement.CreateTable ct = assertInstanceOf(Statement.CreateTable.class, q.statements().get(0));
        assertEquals("person", ct.name());
        assertTrue(ct.schemafull());
        assertEquals(6, ct.columns().size());

        Statement.ColumnDef idCol = ct.columns().get(0);
        assertEquals("id", idCol.name());
        assertEquals("string", idCol.type());
        assertTrue(idCol.primaryKey());

        Statement.ColumnDef ageCol = ct.columns().get(2);
        assertEquals("age", ageCol.name());
        assertEquals("int", ageCol.type());
        assertNotNull(ageCol.defaultExpr());
        assertNotNull(ageCol.checkExpr());
    }

    @Test
    void parseInsertValuesSqlFirst() {
        String sql = "INSERT INTO person (id, name, age) VALUES ('u_1', 'Ana', 30), ('u_2', 'Bob', 25)";
        Query q = AxonQl.parse(sql);
        assertEquals(1, q.statements().size());
        Statement.Insert ins = assertInstanceOf(Statement.Insert.class, q.statements().get(0));
        assertEquals("person", ins.table());
        Expr.ArrayLit arr = assertInstanceOf(Expr.ArrayLit.class, ins.data());
        assertEquals(2, arr.items().size());
    }

    @Test
    void roundTripUpdateSet() {
        assertRoundTrip("UPDATE person SET age = 31 WHERE name = \"Ana\"");
    }

    @Test
    void roundTripSelectAnd() {
        assertRoundTrip("SELECT name FROM person WHERE age >= 18 AND active = true");
    }

    @Test
    void roundTripJoin() {
        assertRoundTrip("SELECT * FROM pedido JOIN cliente ON pedido.cliente = cliente.id");
    }

    @Test
    void joinGuardaFonteEClaves() {
        Query q = AxonQl.parse("SELECT * FROM a JOIN b ON a.k = b.k WHERE a.x = 1");
        var sel = (Statement.Select) q.statements().get(0);
        assertEquals(1, sel.joins().size());
        var join = sel.joins().get(0);
        var src = (Expr.Ident) join.source();
        assertEquals("b", src.name());
        var left = (Expr.Idiom) join.left();
        var right = (Expr.Idiom) join.right();
        assertEquals("a.k", Render.expr(left));
        assertEquals("b.k", Render.expr(right));
    }

    @Test
    void astEstructuraCreate() {
        Query q = AxonQl.parse("CREATE person CONTENT { name: \"Ana\" }");
        assertEquals(1, q.statements().size());
        var s = q.statements().get(0);
        assertInstanceOf(Statement.Create.class, s);
    }

    @Test
    void recordIdParsed() {
        Query q = AxonQl.parse("SELECT * FROM person:1");
        var sel = (Statement.Select) q.statements().get(0);
        assertInstanceOf(Expr.RecordId.class, sel.from());
    }

    @Test
    void precedenceMultiplicacaoAntesSuma() {
        Query q = AxonQl.parse("SELECT * FROM t WHERE a + b * 2 > 5");
        var sel = (Statement.Select) q.statements().get(0);
        var gt = (Expr.Binary) sel.cond();
        assertEquals(Expr.BinaryOp.GT, gt.op());
        var sum = (Expr.Binary) gt.left();
        assertEquals(Expr.BinaryOp.ADD, sum.op());
        var mul = (Expr.Binary) sum.right();
        assertEquals(Expr.BinaryOp.MUL, mul.op());
    }

    @Test
    void letValorLiteral() {
        Query q = AxonQl.parse("LET $x = 10");
        var let = (Statement.Let) q.statements().get(0);
        assertEquals("x", let.name());
        var lit = (Expr.Literal) let.value();
        assertEquals(AxonValue.num(10L), lit.value());
    }

    @Test
    void roundTripInsert() {
        assertRoundTrip("INSERT INTO person [{name: \"Ana\", age: 30}]");
    }

    @Test
    void roundTripRelate() {
        assertRoundTrip("RELATE user:tobie->wrote->article:surreal SET time = 1");
    }

    @Test
    void roundTripDefineIndex() {
        assertRoundTrip("DEFINE INDEX email ON TABLE user COLUMNS email UNIQUE");
    }

    @Test
    void roundTripDefineEvent() {
        assertRoundTrip("DEFINE EVENT email ON TABLE user WHEN $before.email != $after.email THEN (CREATE event SET user = $value)");
    }

    @Test
    void roundTripReturn() {
        assertRoundTrip("RETURN 1 + 2");
    }

    @Test
    void roundTripIfElse() {
        assertRoundTrip("IF $age >= 18 THEN \"adulto\" ELSE \"menor\" END");
    }

    @Test
    void roundTripCast() {
        assertRoundTrip("CREATE person SET age = <int> \"34\"");
    }

    @Test
    void parseAritmeticaPrecedencia() {
        Query q = AxonQl.parse("RETURN 1 + 2 * 3");
        var ret = (Statement.Return) q.statements().get(0);
        var plus = (Expr.Binary) ret.value();
        assertEquals(Expr.BinaryOp.ADD, plus.op());
        var mul = (Expr.Binary) plus.right();
        assertEquals(Expr.BinaryOp.MUL, mul.op());
    }

    @Test
    void roundTripDefineTablePermissions() {
        assertRoundTrip("DEFINE TABLE post SCHEMALESS PERMISSIONS FOR select WHERE published = true");
    }

    @Test
    void roundTripAgreggation() {
        assertRoundTrip("SELECT count() FROM person");
        assertRoundTrip("SELECT sum(valor) FROM venda GROUP BY cidade");
    }

    @Test
    void roundTripLiveSelect() {
        assertRoundTrip("LIVE SELECT * FROM person");
        assertRoundTrip("LIVE SELECT * FROM person WHERE age > 18");
        assertRoundTrip("LIVE SELECT * FROM person DIFF");
    }

    @Test
    void parseLiveGuardaSelectEDiff() {
        Query q = AxonQl.parse("LIVE SELECT name FROM person DIFF");
        Statement.Live live = assertInstanceOf(Statement.Live.class, q.statements().get(0));
        assertEquals(true, live.diff());
        assertEquals("person", ((Expr.Ident) live.select().from()).name());
    }

    @Test
    void roundTripSubqueriesAliasesEOperadoresDeColecao() {
        assertRoundTrip("SELECT VALUE name FROM person");
        assertRoundTrip("SELECT string::uppercase(name) AS grito FROM person");
        assertRoundTrip("SELECT * FROM (SELECT name FROM person)");
        assertRoundTrip("SELECT * FROM person WHERE cidade INSIDE (SELECT VALUE nome FROM cidade)");
        assertRoundTrip("SELECT * FROM person WHERE tags NOT CONTAINS \"vip\"");
        assertRoundTrip("SELECT * FROM person ORDER BY age DESC");
    }

    @Test
    void roundTripDefineUserEAccess() {
        assertRoundTrip("DEFINE USER alice ON DATABASE PASSWORD \"segredo\" ROLES editor, writer");
        assertRoundTrip("DEFINE ACCESS app_login ON NAMESPACE app");
    }
    @Test
    void roundTripDefineFieldReferences() {
        assertRoundTrip("DEFINE FIELD autor ON TABLE post REFERENCES user");
    }

    @Test
    void roundTripDefineUserComPasshash() {
        // A forma PASSHASH é o que o plano de controle replica: salt e hash juntos,
        // sem a senha original, para que o replay não precise rehashear nada.
        assertRoundTrip("DEFINE USER alice ON ROOT PASSHASH \"a1b2:c3d4\"");
        assertRoundTrip("DEFINE USER alice ON DATABASE PASSHASH \"a1b2:c3d4\" ROLES editor");
    }

    @Test
    void roundTripCreateJks() {
        assertRoundTrip("CREATE JKS clients PATH \"/etc/axonbase/clients.jks\" PASSWORD \"changeit\"");
    }

    @Test
    void createJksGuardaConfiguracao() {
        Query q = AxonQl.parse("CREATE JKS clients PATH \"/etc/axonbase/clients.jks\" PASSWORD \"changeit\"");
        Statement.CreateJks jks = assertInstanceOf(Statement.CreateJks.class, q.statements().get(0));

        assertEquals("clients", jks.name());
        assertEquals("/etc/axonbase/clients.jks", jks.path());
        assertEquals("changeit", jks.password());
    }

    @Test
    void createJksSupportsIcpBrasilAndOidCollectors() {
        assertRoundTrip("CREATE JKS icp_brasil PATH \"/etc/axonbase/icpbrasil.jks\" PASSWORD \"changeit\" COLLECT USER BY ICPBRASIL");
        assertRoundTrip("CREATE JKS company PATH \"/etc/axonbase/company.jks\" PASSWORD \"changeit\" COLLECT USER BY OID \"2.5.4.5\", \"2.16.76.1.3.1\"");

        Statement.CreateJks jks = assertInstanceOf(Statement.CreateJks.class,
            AxonQl.parse("CREATE JKS company PATH \"/etc/axonbase/company.jks\" PASSWORD \"changeit\" COLLECT USER BY OID \"2.5.4.5\", \"2.16.76.1.3.1\"").statements().get(0));
        assertEquals("OID", jks.collector());
        assertEquals(java.util.List.of("2.5.4.5", "2.16.76.1.3.1"), jks.oids());
    }

    @Test
    void roundTripDefineUserComCertificado() {
        assertRoundTrip("DEFINE USER alice ON ROOT CERTIFICATE clients ROLES editor");
        assertRoundTrip("DEFINE USER alice ON NAMESPACE app CERTIFICATE clients FINGERPRINT \"AB:CD\" ROLES editor, writer");
        assertRoundTrip("DEFINE USER alice ON DATABASE app CERTIFICATE clients FINGERPRINT \"AB:CD\"");
    }

    @Test
    void defineUserComCertificadoGuardaJksEFingerprint() {
        Query q = AxonQl.parse("DEFINE USER alice ON ROOT CERTIFICATE clients FINGERPRINT \"AB:CD\" ROLES editor");
        Statement.DefineUser user = assertInstanceOf(Statement.DefineUser.class, q.statements().get(0));

        assertEquals("clients", user.certificate());
        assertEquals("AB:CD", user.fingerprint());
    }

    @Test
    void defineUserExigePasswordOuPasshash() {
        assertThrows(AxonError.class, () -> AxonQl.parse("DEFINE USER alice ON ROOT"));
    }

    @Test
    void roundTripFullText() {
        assertRoundTrip("DEFINE ANALYZER pt LOWERCASE STOPWORDS \"o\", \"a\" STEMMING");
        assertRoundTrip("DEFINE INDEX body_search ON TABLE article COLUMNS body SEARCH ANALYZER pt");
        assertRoundTrip("SELECT * FROM article WHERE body @@ \"gato rápido\"");
        assertRoundTrip("DEFINE INDEX point_geo ON TABLE place COLUMNS point GEO");
        assertRoundTrip("DEFINE INDEX embedding_hnsw ON TABLE item COLUMNS embedding HNSW DIMENSION 3 DIST euclidean");
    }

    @Test
    void roundTripHybridSearch() {
        assertRoundTrip("SELECT name, search::score() AS score FROM item "
            + "WHERE body @@ \"topic\" AND vector::similarity::cosine(embedding, [1, 0, 0]) > 0 "
            + "ORDER BY search::score() DESC LIMIT 2");
        assertRoundTrip("EXPLAIN SELECT * FROM item "
            + "WHERE body @@ \"topic\" AND vector::similarity::cosine(embedding, [1, 0]) > 0");
    }

    @Test
    void parseErroInvalido() {
        assertThrows(AxonError.class, () -> AxonQl.parse("SELECT FROM "));
    }

    @Test
    void mensagensDoParserPreservamPosicaoEmIngles() {
        assertEquals("expected EOF at position 7 but found 'FROM'",
            Messages.getForLanguage("en", "parser_expected_token", "EOF", 7, "FROM"));
    }

    @Test
    void roundTripExplain() {
        assertRoundTrip("EXPLAIN SELECT * FROM person");
        assertRoundTrip("EXPLAIN SELECT name FROM person WHERE age > 18");
        assertRoundTrip("EXPLAIN ANALYZE SELECT * FROM person");
        assertRoundTrip("EXPLAIN ANALYZE SELECT name FROM person WHERE age = 18");
    }

    @Test
    void explainGuardaSelectEAnalyze() {
        Query q = AxonQl.parse("EXPLAIN ANALYZE SELECT name FROM person WHERE age = 18");
        Statement.Explain ex = assertInstanceOf(Statement.Explain.class, q.statements().get(0));
        assertEquals(true, ex.analyze());
        assertEquals("person", ((Expr.Ident) ex.select().from()).name());
    }

    @Test
    void roundTripSavepoints() {
        assertRoundTrip("SAVEPOINT sp1");
        assertRoundTrip("RELEASE sp1");
        assertRoundTrip("ROLLBACK TO sp1");
        assertRoundTrip("SAVEPOINT a; RELEASE a; SAVEPOINT b; ROLLBACK TO b");
    }

    @Test
    void savepointsFormaLongaEQuivalente() {
        Query full = AxonQl.parse("RELEASE SAVEPOINT sp1; ROLLBACK TO SAVEPOINT sp2; SAVEPOINT sp3");
        Query shortForm = AxonQl.parse("RELEASE sp1; ROLLBACK TO sp2; SAVEPOINT sp3");
        assertEquals(AxonQl.render(shortForm), AxonQl.render(full));
        assertEquals(3, full.statements().size());
        Statement.Release rel = assertInstanceOf(Statement.Release.class, full.statements().get(0));
        assertEquals("sp1", rel.name());
        Statement.RollbackTo rb = assertInstanceOf(Statement.RollbackTo.class,
            full.statements().get(1));
        assertEquals("sp2", rb.name());
        Statement.Savepoint sp = assertInstanceOf(Statement.Savepoint.class,
            full.statements().get(2));
        assertEquals("sp3", sp.name());
    }
}
