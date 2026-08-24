package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FunctionAndSubqueryTest {

    private Datastore ds() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        return ds;
    }

    private Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    @Test
    void funcoesDeStringArrayObjetoTipoTempoECripto() {
        Datastore ds = ds();
        Session s = session();
        assertEquals("OLÁ", ds.execute("RETURN string::uppercase(\"olá\")", s, null).asString());
        assertEquals("ola-mundo", ds.execute("RETURN string::slug(\"Olá, Mundo!\")", s, null).asString());
        assertEquals(3, ds.execute("RETURN array::distinct([1, 1, 2, 3])", s, null).asArray().size());
        assertEquals(3, ds.execute("RETURN array::sort::desc([1, 3, 2])", s, null).asArray().get(0).asLong());
        assertEquals("a", ds.execute("RETURN object::keys({a: 1})[0]", s, null).asString());
        assertTrue(ds.execute("RETURN type::is::number(42)", s, null).asBool());
        assertEquals(64, ds.execute("RETURN string::len(crypto::sha256(\"abc\"))", s, null).asLong());
        assertTrue(ds.execute("RETURN time::year(time::now())", s, null).asLong() >= 2026);
    }

    @Test
    void subqueryFuncionaComoExpressaoOrigemEFiltro() {
        Datastore ds = ds();
        Session s = session();
        ds.execute("CREATE person:ana CONTENT {name: \"Ana\", city: \"SP\"}", s, null);
        ds.execute("CREATE person:bob CONTENT {name: \"Bob\", city: \"RJ\"}", s, null);
        ds.execute("CREATE city:sp CONTENT {name: \"SP\"}", s, null);

        AxonValue in = ds.execute("SELECT VALUE name FROM person WHERE city IN "
            + "(SELECT VALUE name FROM city)", s, null);
        assertEquals(1, in.asArray().size());
        assertEquals("Ana", in.asArray().get(0).asString());

        AxonValue derived = ds.execute("SELECT * FROM (SELECT name FROM person)", s, null);
        assertEquals(2, derived.asArray().size());

        AxonValue correlated = ds.execute("SELECT name, (SELECT VALUE name FROM city "
            + "WHERE name = $parent.city) AS city FROM person", s, null);
        assertEquals("SP", correlated.asArray().get(0).asObject().get("city").asArray()
            .get(0).asString());
    }
}
