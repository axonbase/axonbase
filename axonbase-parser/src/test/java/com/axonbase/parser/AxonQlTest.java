package com.axonbase.parser;

import com.axonbase.common.AxonError;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Query;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void roundTripSelectWhere() {
        assertRoundTrip("SELECT * FROM person WHERE age > 18");
    }

    @Test
    void roundTripDefineTable() {
        assertRoundTrip("DEFINE TABLE user SCHEMAFULL");
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
    void roundTripDefineUserComPasshash() {
        // A forma PASSHASH é o que o plano de controle replica: salt e hash juntos,
        // sem a senha original, para que o replay não precise rehashear nada.
        assertRoundTrip("DEFINE USER alice ON ROOT PASSHASH \"a1b2:c3d4\"");
        assertRoundTrip("DEFINE USER alice ON DATABASE PASSHASH \"a1b2:c3d4\" ROLES editor");
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
    void parseErroInvalido() {
        assertThrows(AxonError.class, () -> AxonQl.parse("SELECT FROM "));
    }
}
