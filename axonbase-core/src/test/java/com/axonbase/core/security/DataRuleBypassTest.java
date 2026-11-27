package com.axonbase.core.security;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Caminhos pelos quais um usuário com Data Rule lia ou gravava o que a regra protege. */
class DataRuleBypassTest {

    private static Session anonymous(String database) {
        Session s = Session.create();
        s.namespace("app");
        s.database(database);
        return s;
    }

    private static Session admin() {
        return anonymous("main");
    }

    private static Session restricted(String rules, String database) {
        Session s = anonymous(database);
        Map<String, AxonValue> claims = new LinkedHashMap<>();
        claims.put("id", AxonValue.str("v"));
        claims.put("scope", AxonValue.str("NAMESPACE"));
        claims.put("ns", AxonValue.str("app"));
        claims.put("roles", AxonValue.str("editor"));
        claims.put("data_rules", AxonValue.str(rules));
        s.auth(AxonValue.object(claims));
        return s;
    }

    private static Session v() {
        return restricted("meus", "main");
    }

    private static Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.createDatabase("app", "outro");
        ds.execute("CREATE o:1 CONTENT {rep: 1, cpf: '111'}", admin(), null);
        ds.execute("CREATE o:2 CONTENT {rep: 2, cpf: '222'}", admin(), null);
        ds.execute("CREATE DATA RULE meus APPLY rep = 1 MASK FIELDS WITH '%cpf%'", admin(), null);
        return ds;
    }

    private static String text(Datastore ds, String query, Session s) {
        return ds.execute(query, s, null).toString();
    }

    @Test
    void regraInexistenteNegaEmVezDeLiberar() {
        Datastore ds = ds();
        assertEquals(0, ds.execute("SELECT * FROM o", restricted("nao_existe", "main"), null).asArray().size());
        assertEquals(0, ds.execute("SELECT * FROM o", restricted("meus,nao_existe", "main"), null).asArray().size());
        assertThrows(AxonError.class,
            () -> ds.execute("CREATE o:9 CONTENT {rep: 1}", restricted("nao_existe", "main"), null));
    }

    @Test
    void regraDefinidaEmOutroBancoNegaNesteBanco() {
        Datastore ds = ds();
        ds.execute("CREATE o:1 CONTENT {rep: 1, cpf: '333'}", anonymous("outro"), null);
        assertEquals(0, ds.execute("SELECT * FROM o", restricted("meus", "outro"), null).asArray().size());
    }

    @Test
    void retornoDeEscritaVemMascarado() {
        Datastore ds = ds();
        assertFalse(text(ds, "UPDATE o:1 SET nota = 1", v()).contains("111"));
        assertFalse(text(ds, "CREATE o:3 CONTENT {cpf: '444'}", v()).contains("444"));
        assertFalse(text(ds, "INSERT INTO o [{id: 5, cpf: '555'}]", v()).contains("555"));
        assertFalse(text(ds, "DELETE o:1 RETURN BEFORE", v()).contains("111"));
    }

    @Test
    void selectValueApelidoEOnlyVemMascarados() {
        Datastore ds = ds();
        assertFalse(text(ds, "SELECT VALUE cpf FROM o", v()).contains("111"));
        assertFalse(text(ds, "SELECT cpf AS x FROM o", v()).contains("111"));
        assertFalse(text(ds, "SELECT VALUE cpf FROM o:1", v()).contains("111"));
        assertFalse(text(ds, "SELECT max(cpf) AS maior FROM o GROUP ALL", v()).contains("111"));
    }

    @Test
    void acessoDiretoPorIdRespeitaFiltroEMascara() {
        Datastore ds = ds();
        assertFalse(text(ds, "RETURN o:2.cpf", v()).contains("222"));
        assertFalse(text(ds, "RETURN o:1.cpf", v()).contains("111"));
    }

    @Test
    void fetchNaoExpandeRegistroOculto() {
        Datastore ds = ds();
        ds.execute("CREATE p:1 CONTENT {rep: 1, pedido: o:2}", admin(), null);
        assertFalse(text(ds, "SELECT * FROM p FETCH pedido", v()).contains("222"));
    }

    @Test
    void regraQueAtravessaUmLinkContinuaValendo() {
        Datastore ds = ds();
        ds.execute("CREATE u:1 CONTENT {equipe: 'a'}", admin(), null);
        ds.execute("CREATE d:1 CONTENT {dono: u:1}", admin(), null);
        ds.execute("CREATE DATA RULE equipe_a APPLY dono.equipe = 'a'", admin(), null);
        assertEquals(1, ds.execute("SELECT * FROM d", restricted("equipe_a", "main"), null).asArray().size());
    }

    @Test
    void upsertECreateNaoTocamRegistroOculto() {
        Datastore ds = ds();
        // o motor também recusa criar sobre um id existente; sobre um registro oculto a recusa tem de ser a de
        // permissão, que não diz que ele existe, e INSERT IGNORE não o pula em silêncio
        for (String write : new String[] {"UPSERT o:2 SET z = 1", "CREATE o:2 CONTENT {z: 2}",
            "INSERT INTO o [{id: 2, z: 3}]", "INSERT IGNORE INTO o [{id: 2, z: 4}]"}) {
            AxonError refused = assertThrows(AxonError.class, () -> ds.execute(write, v(), null), write);
            assertTrue(refused.getMessage().contains("permission denied"), refused.getMessage());
        }
        assertEquals("222", ds.execute("RETURN o:2.cpf", admin(), null).asString());
        assertEquals(2L, ds.execute("RETURN o:2.rep", admin(), null).asLong());
    }

    @Test
    void caminhosAninhadosDeEscritaRespeitamFiltroEMascara() {
        Datastore ds = ds();
        assertFalse(text(ds, "UPDATE o SET dados.a = 1", v()).contains("111"));
        assertFalse(text(ds, "UPDATE o MERGE {dados: {b: 2}}", v()).contains("111"));
        assertFalse(text(ds, "UPDATE o PATCH [{op: 'add', path: '/dados/c', value: 3}]", v()).contains("111"));
        assertFalse(text(ds, "SELECT * FROM o:2", admin()).contains("dados"));
        for (String patch : new String[] {"{op: 'copy', from: '/cpf', path: '/x'}",
            "{op: 'move', from: '/cpf', path: '/x'}", "{op: 'test', path: '/cpf', value: '111'}",
            "{op: 'copy', from: '', path: '/x'}", "{op: 'remove', path: '/cpf/0'}"}) {
            AxonError refused = assertThrows(AxonError.class,
                () -> ds.execute("UPDATE o:1 PATCH [" + patch + "]", v(), null), patch);
            assertTrue(refused.getMessage().contains("permission denied"), refused.getMessage());
        }
        assertFalse(text(ds, "DELETE FROM o RETURN BEFORE", v()).contains("111"));
        assertEquals("222", ds.execute("RETURN o:2.cpf", admin(), null).asString());
    }

    @Test
    void liveERecusadoAUsuarioComRegra() {
        Datastore ds = ds();
        assertThrows(AxonError.class, () -> ds.execute("LIVE SELECT * FROM o", v(), null));
    }
}
