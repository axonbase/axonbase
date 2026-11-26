package com.axonbase.core.shard;

import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: roteamento pela condição e separação de scripts")
class ShardRoutingTest {

    private static Expr where(String condition) {
        return ((Statement.Select) AxonQl.parse("SELECT * FROM t WHERE " + condition).statements().get(0)).cond();
    }

    private static Optional<List<AxonValue>> restriction(String condition) {
        return ShardRouting.restriction(where(condition), "k", expr -> switch (expr) {
            case Expr.Literal literal -> literal.value();
            case Expr.ArrayLit array -> AxonValue.array(array.items().stream()
                .map(item -> ((Expr.Literal) item).value()).toList());
            default -> AxonValue.str("param");
        });
    }

    @Test
    void igualdadeEInsideSobreAChaveRestringem() {
        assertEquals(List.of(AxonValue.str("a")), restriction("k = 'a'").orElseThrow());
        assertEquals(List.of(AxonValue.num(1)), restriction("1 = k").orElseThrow());
        assertEquals(List.of(AxonValue.num(1), AxonValue.num(2)), restriction("k INSIDE [1, 2]").orElseThrow());
        assertEquals(List.of(AxonValue.str("a")), restriction("total > 3 AND k = 'a'").orElseThrow());
        assertEquals(List.of(AxonValue.str("param")), restriction("k = $x").orElseThrow());
    }

    @Test
    void aConjuncaoUsaAParteMaisEstreita() {
        assertEquals(List.of(AxonValue.num(2)), restriction("k INSIDE [1, 2, 3] AND k = 2").orElseThrow());
    }

    @Test
    void outrasFormasNaoRestringem() {
        for (String condition : List.of("k = 'a' OR total = 1", "k != 'a'", "k > 3", "outra = 'a'", "k = total",
            "k = NONE", "k = '2026-11-26T10:00:00Z'", "NOT (k = 'a')", "k INSIDE [1, total]")) {
            assertTrue(restriction(condition).isEmpty(), condition);
        }
        assertTrue(ShardRouting.restriction(null, "k", expr -> AxonValue.num(1)).isEmpty());
    }

    @Test
    void scriptESeparadoSemQuebrarBlocosNemTextos() {
        List<ScriptStatements.Piece> pieces = ScriptStatements.split(
            "CREATE t SET a = 'x;y', b = [1, 2];\n SELECT * FROM t WHERE a = '}' ;; "
                + "DEFINE EVENT e ON TABLE t WHEN true THEN (CREATE log SET m = 1);");

        assertEquals(3, pieces.size());
        assertEquals("CREATE t SET a = 'x;y', b = [1, 2]", pieces.get(0).text());
        assertEquals("SELECT * FROM t WHERE a = '}'", pieces.get(1).text());
        assertTrue(pieces.get(2).statement() instanceof Statement.DefineEvent);
    }

    @Test
    void varreduraAchaOQueAtravessaTabelas() {
        StatementScan nested = StatementScan.of(AxonQl.parse(
            "SELECT * FROM a WHERE x INSIDE (SELECT VALUE id FROM b)").statements().get(0));

        assertTrue(nested.subquery());
        assertTrue(nested.subqueryTables().contains("b"));
        assertTrue(StatementScan.of(AxonQl.parse("SELECT ->comprou->produto FROM pessoa").statements().get(0)).graph());
        assertTrue(StatementScan.of(AxonQl.parse("SELECT * FROM a WHERE t @@ 'x'").statements().get(0)).match());
        assertTrue(StatementScan.of(AxonQl.parse("SELECT fn::f(1) FROM a").statements().get(0)).program());
        StatementScan plain = StatementScan.of(AxonQl.parse(
            "SELECT count(), math::sum(x) FROM a WHERE y.z = 1 GROUP BY y").statements().get(0));
        assertTrue(!plain.subquery() && !plain.graph() && !plain.match() && !plain.program());
    }
}
