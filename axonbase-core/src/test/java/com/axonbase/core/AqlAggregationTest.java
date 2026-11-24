package com.axonbase.core;

import com.axonbase.common.AxonError;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GROUP BY, GROUP ALL, HAVING, ORDER BY e LIMIT sobre grupos, count(DISTINCT) e expressões de agregados. */
class AqlAggregationTest {

    private Datastore ds;
    private Session session;

    @BeforeEach
    void setUp() {
        ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        session = Session.create();
        session.namespace("test");
        session.database("dev");
        // SP: 3 vendas de 2 clientes (total 60), RJ: 2 vendas de 2 clientes (total 20), BH: 1 venda (total 10)
        run("INSERT INTO venda [{cidade: 'SP', cliente: 'ana', valor: 10}, {cidade: 'SP', cliente: 'ana', valor: 20},"
            + " {cidade: 'SP', cliente: 'bia', valor: 30}, {cidade: 'RJ', cliente: 'caio', valor: 5},"
            + " {cidade: 'RJ', cliente: 'dani', valor: 15}, {cidade: 'BH', cliente: 'edu', valor: 10}]");
    }

    private AxonValue run(String sql) {
        return ds.execute(sql, session, null);
    }

    private List<AxonValue> rows(String sql) {
        return run(sql).asArray();
    }

    private static long num(AxonValue row, String key) {
        return row.asObject().get(key).asLong();
    }

    private static List<String> column(List<AxonValue> rows, String key) {
        List<String> out = new ArrayList<>();
        for (AxonValue r : rows) {
            out.add(r.asObject().get(key).isString() ? r.asObject().get(key).asString() : String.valueOf(r.asObject().get(key).asLong()));
        }
        return out;
    }

    // ------------------------------------------------------- nomes das colunas

    @Test
    void colunaDeGrupoSelecionadaSaiComONomeDoCampo() {
        List<AxonValue> rows = rows("SELECT cidade, sum(valor) AS total FROM venda GROUP BY cidade");
        assertEquals(3, rows.size());
        for (AxonValue r : rows) {
            assertTrue(r.asObject().containsKey("cidade"));
            assertTrue(r.asObject().containsKey("total"));
            assertFalse(r.asObject().containsKey("group.0"), "grupo selecionado não repete group.0");
        }
    }

    @Test
    void grupoForaDoSelectContinuaComoGroupN() {
        List<AxonValue> rows = rows("SELECT count() FROM venda GROUP BY cidade");
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).asObject().containsKey("group.0"));
        assertTrue(rows.get(0).asObject().containsKey("count"));
    }

    // ------------------------------------------------------------- ORDER / LIMIT

    @Test
    void orderByDeAgregadoPeloApelidoEPelaChamada() {
        assertEquals(List.of("SP", "RJ", "BH"),
            column(rows("SELECT cidade, count() AS n FROM venda GROUP BY cidade ORDER BY n DESC"), "cidade"));
        assertEquals(List.of("BH", "RJ", "SP"),
            column(rows("SELECT cidade, sum(valor) AS total FROM venda GROUP BY cidade ORDER BY sum(valor)"), "cidade"));
    }

    @Test
    void orderByComDoisCriteriosDesempataPeloSegundo() {
        List<AxonValue> rows = rows("SELECT cidade, count() AS n FROM venda WHERE cidade != 'SP' GROUP BY cidade ORDER BY n DESC, cidade");
        assertEquals(List.of("RJ", "BH"), column(rows, "cidade"));
        rows = rows("SELECT cidade, count() AS n FROM venda WHERE cidade != 'SP' GROUP BY cidade ORDER BY n, cidade DESC");
        assertEquals(List.of("BH", "RJ"), column(rows, "cidade"));
    }

    @Test
    void limitEStartValemParaOsGruposNaoParaAsLinhasDeOrigem() {
        List<AxonValue> top2 = rows("SELECT cidade, count() AS n FROM venda GROUP BY cidade ORDER BY n DESC LIMIT 2");
        assertEquals(List.of("SP", "RJ"), column(top2, "cidade"));
        assertEquals(3, num(top2.get(0), "n"), "o limite não pode cortar as linhas antes de contar");
        List<AxonValue> second = rows("SELECT cidade, count() AS n FROM venda GROUP BY cidade ORDER BY n DESC LIMIT 1 START 1");
        assertEquals(List.of("RJ"), column(second, "cidade"));
    }

    // ----------------------------------------------------------------- HAVING

    @Test
    void havingFiltraGruposPeloApelidoEPelaChamada() {
        assertEquals(List.of("SP", "RJ"), column(
            rows("SELECT cidade, count() AS n FROM venda GROUP BY cidade HAVING n > 1 ORDER BY n DESC"), "cidade"));
        assertEquals(List.of("SP"), column(
            rows("SELECT cidade, sum(valor) AS total FROM venda GROUP BY cidade HAVING sum(valor) >= 60"), "cidade"));
    }

    @Test
    void havingSemGroupDaErroDeSintaxe() {
        assertThrows(AxonError.class, () -> run("SELECT count() FROM venda HAVING count() > 1"));
    }

    // ------------------------------------------------------ apelido em GROUP BY

    @Test
    void groupByPeloApelidoDeUmCampoCalculado() {
        run("INSERT INTO ev [{quando: <datetime>'2026-11-20T10:05:00Z'}, {quando: <datetime>'2026-11-20T10:50:00Z'},"
            + " {quando: <datetime>'2026-11-20T11:00:00Z'}]");
        List<AxonValue> rows = rows("SELECT time::hour(quando) AS h, count() AS n FROM ev GROUP BY h ORDER BY h");
        assertEquals(2, rows.size());
        assertEquals(10, num(rows.get(0), "h"));
        assertEquals(2, num(rows.get(0), "n"));
        assertEquals(11, num(rows.get(1), "h"));
    }

    // -------------------------------------------------------------- GROUP ALL

    @Test
    void groupAllAgregaTudoEmUmaLinha() {
        List<AxonValue> rows = rows("SELECT count() AS n, sum(valor) AS total, min(valor) AS menor, max(valor) AS maior FROM venda GROUP ALL");
        assertEquals(1, rows.size());
        assertEquals(6, num(rows.get(0), "n"));
        assertEquals(90, num(rows.get(0), "total"));
        assertEquals(5, num(rows.get(0), "menor"));
        assertEquals(30, num(rows.get(0), "maior"));
    }

    @Test
    void agregadoSemGrupoEmTabelaVaziaDevolveZero() {
        List<AxonValue> rows = rows("SELECT count() AS n FROM nada GROUP ALL");
        assertEquals(1, rows.size());
        assertEquals(0, num(rows.get(0), "n"));
        assertEquals(0, num(rows("SELECT count() AS n FROM nada").get(0), "n"));
    }

    @Test
    void groupByEmTabelaVaziaNaoDevolveLinhas() {
        assertEquals(0, rows("SELECT count() AS n FROM nada GROUP BY x").size());
    }

    // ------------------------------------------------------ count(DISTINCT x)

    @Test
    void countDistinctContaValoresDiferentes() {
        List<AxonValue> rows = rows("SELECT cidade, count() AS vendas, count(DISTINCT cliente) AS clientes FROM venda GROUP BY cidade ORDER BY vendas DESC");
        assertEquals(3, num(rows.get(0), "vendas"));
        assertEquals(2, num(rows.get(0), "clientes"), "SP tem 3 vendas de 2 clientes");
        assertEquals(1, num(rows.get(2), "clientes"));
    }

    @Test
    void countDistinctIgnoraNuloEUmEUmPontoZeroSaoOMesmoValor() {
        run("INSERT INTO m [{v: 1}, {v: 1.0}, {v: 2}, {w: 9}]");
        assertEquals(2, num(rows("SELECT count(DISTINCT v) AS d FROM m GROUP ALL").get(0), "d"));
    }

    @Test
    void distintoContinuaUsavelComoNomeDeCampo() {
        run("INSERT INTO c [{distinct: 3}, {distinct: 4}]");
        assertEquals(7, num(rows("SELECT sum(distinct) AS s FROM c GROUP ALL").get(0), "s"));
    }

    // ------------------------------------------------ expressões de agregados

    @Test
    void expressaoComVariosAgregados() {
        List<AxonValue> rows = rows("SELECT cidade, sum(valor) / count() AS media FROM venda WHERE cidade = 'SP' GROUP BY cidade");
        assertEquals(1, rows.size());
        assertEquals(20, num(rows.get(0), "media"));
        assertEquals(26, num(rows("SELECT max(valor) - min(valor) + 1 AS faixa FROM venda GROUP ALL").get(0), "faixa"));
    }

    @Test
    void avgArredondaEmDezCasasEmVezDeFalhar() {
        List<AxonValue> rows = rows("SELECT avg(valor) AS media FROM venda WHERE cidade = 'SP' OR cidade = 'BH' GROUP ALL");
        assertEquals(1, rows.size());
        // (10 + 20 + 30 + 10) / 4 = 17.5, exato; e 1/3 não é: precisa arredondar
        assertEquals("17.5", rows.get(0).asObject().get("media").asDecimal().toPlainString());
        run("INSERT INTO t3 [{x: 1}, {x: 1}, {x: 2}]");
        assertEquals("1.3333333333", rows("SELECT avg(x) AS m FROM t3 GROUP ALL").get(0).asObject().get("m").asDecimal().toPlainString());
    }

    // ------------------------------------------------------ ordem das cláusulas

    @Test
    void asClausulasPodemVirEmQualquerOrdem() {
        List<String> padrao = column(rows(
            "SELECT cidade, count() AS n FROM venda WHERE valor > 0 GROUP BY cidade HAVING n >= 1 ORDER BY n DESC LIMIT 2"), "cidade");
        List<String> antigo = column(rows(
            "SELECT cidade, count() AS n FROM venda WHERE valor > 0 ORDER BY n DESC GROUP BY cidade HAVING n >= 1 LIMIT 2"), "cidade");
        assertEquals(List.of("SP", "RJ"), padrao);
        assertEquals(padrao, antigo);
    }

    // ------------------------------------------------------------ SELECT VALUE

    @Test
    void selectValueDeAgregadoSemGrupoDevolveONumero() {
        AxonValue n = run("SELECT VALUE count() FROM venda");
        assertTrue(n.isNumber(), n.toString());
        assertEquals(6, n.asLong());
        assertEquals(3, run("SELECT VALUE count() FROM venda WHERE cidade = 'SP'").asLong());
    }

    @Test
    void selectValueComGrupoDevolveArrayDeValores() {
        AxonValue out = run("SELECT VALUE count() FROM venda GROUP BY cidade");
        assertTrue(out.isArray());
        assertEquals(3, out.asArray().size());
    }

    @Test
    void subqueryDeContagemServeDeColunaNumaConsultaExterna() {
        run("INSERT INTO regiao [{id: 'a', cidade: 'SP'}, {id: 'b', cidade: 'RJ'}]");
        List<AxonValue> rows = rows("SELECT cidade, (SELECT VALUE count() FROM venda WHERE cidade = $parent.cidade) AS vendas FROM regiao ORDER BY cidade");
        assertEquals(2, rows.size());
        assertEquals(2, rows.get(0).asObject().get("vendas").asLong());
        assertEquals(3, rows.get(1).asObject().get("vendas").asLong());
    }

    // ------------------------------------------------ comportamento antigo vivo

    @Test
    void agregadoSimplesContinuaIgual() {
        assertEquals(6, num(rows("SELECT count() FROM venda").get(0), "count"));
        assertEquals(90, num(rows("SELECT sum(valor) FROM venda").get(0), "sum"));
    }

    // -------------------------------------------- a consulta de congestionamento

    @Test
    void consultaDeCongestionamentoComCelulaJanelaDeHorarioEDistinct() {
        // célula A (muito movimentada, 18:15 em Brasília), célula B (pouco), e um ping fora da janela (17:00 local)
        String a = "{type:'Point', coordinates:[-46.6500, -23.5600]}";
        String a2 = "{type:'Point', coordinates:[-46.6501, -23.5601]}";
        String b = "{type:'Point', coordinates:[-46.7000, -23.6000]}";
        run("INSERT INTO transito ["
            + "{quando: <datetime>'2026-11-20T21:15:00Z', placa: 'AAA1A11', pos: " + a + "},"
            + "{quando: <datetime>'2026-11-20T21:16:00Z', placa: 'AAA1A11', pos: " + a2 + "},"
            + "{quando: <datetime>'2026-11-20T21:17:00Z', placa: 'BBB2B22', pos: " + a + "},"
            + "{quando: <datetime>'2026-11-20T21:20:00Z', placa: 'CCC3C33', pos: " + b + "},"
            + "{quando: <datetime>'2026-11-20T20:00:00Z', placa: 'AAA1A11', pos: " + a + "}]");
        List<AxonValue> rows = rows("SELECT geo::cell(pos, 500) AS trecho, count() AS pings, count(DISTINCT placa) AS carros"
            + " FROM transito WHERE time::clock(quando, 'America/Sao_Paulo') BETWEEN '18:00' AND '19:30'"
            + " GROUP BY trecho ORDER BY pings DESC LIMIT 10");
        assertEquals(2, rows.size());
        assertEquals("Polygon", rows.get(0).asObject().get("trecho").asObject().get("type").asString());
        assertEquals(3, num(rows.get(0), "pings"), "3 pings dentro da janela na célula A (o das 17:00 fica de fora)");
        assertEquals(2, num(rows.get(0), "carros"));
        assertEquals(1, num(rows.get(1), "pings"));
    }

    @Test
    void agruparPorJanelaDeTempoComFloor() {
        run("INSERT INTO ev [{quando: <datetime>'2026-11-20T21:01:00Z'}, {quando: <datetime>'2026-11-20T21:09:00Z'},"
            + " {quando: <datetime>'2026-11-20T21:11:00Z'}]");
        List<AxonValue> rows = rows("SELECT time::floor(quando, '10m') AS janela, count() AS n FROM ev GROUP BY janela ORDER BY janela");
        assertEquals(2, rows.size());
        assertEquals(2, num(rows.get(0), "n"));
        assertEquals(1, num(rows.get(1), "n"));
    }
}
