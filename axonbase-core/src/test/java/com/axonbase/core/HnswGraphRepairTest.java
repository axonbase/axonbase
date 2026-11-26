package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * O grafo HNSW continua alcançável depois de apagar e mover linhas. As consultas pedem todas as linhas vivas (ou
 * dez), e o efSearch cobre o grafo inteiro: faltar uma linha significa que não há caminho até ela.
 */
@DisplayName("Grafo vetorial depois de apagar e atualizar linhas")
class HnswGraphRepairTest {

    private static final String DEFINE = "DEFINE INDEX vx ON TABLE p COLUMNS v HNSW DIMENSION 2 DIST euclidean "
        + "M 4 EFC 32 EFS 256";

    private static Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    private static Datastore indexed(Session s) {
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "dev");
        ds.execute(DEFINE, s, null);
        return ds;
    }

    private static void create(Datastore ds, Session s, int id, double x, double y) {
        ds.execute("CREATE p:" + id + " CONTENT { n: " + id + ", v: [" + x + ", " + y + "] }", s, null);
    }

    /** Ids devolvidos pela busca vetorial a partir da origem. */
    private static Set<Long> found(Datastore ds, Session s, int limit) {
        AxonValue rows = ds.execute(
            "SELECT VALUE n FROM p ORDER BY vector::distance::euclidean(v, [0.0, 0.0]) LIMIT " + limit, s, null);
        Set<Long> ids = new TreeSet<>();
        for (AxonValue row : rows.asArray()) {
            ids.add(row.asLong());
        }
        return ids;
    }

    /** Em milésimos, para o literal nunca sair em notação científica. */
    private static double coordinate(Random random) {
        return random.nextInt(1000) / 1000.0;
    }

    @Test
    @DisplayName("apagar os nós que fazem a ponte não desliga parte do grafo")
    void deletingBridgeNodesKeepsTheGraphConnected() {
        Session s = session();
        Datastore ds = indexed(s);
        // dois grupos distantes e quatro nós no meio; nesta ordem o segundo grupo só se liga ao meio, nunca ao primeiro
        for (int i = 0; i < 6; i++) {
            create(ds, s, i, i * 0.01, 0.0);
        }
        for (int i = 0; i < 4; i++) {
            create(ds, s, 10 + i, 0.50 + i * 0.01, 0.0);
        }
        for (int i = 0; i < 6; i++) {
            create(ds, s, 20 + i, 1.00 + i * 0.01, 0.0);
        }

        for (int i = 0; i < 4; i++) {
            ds.execute("DELETE p:" + (10 + i), s, null);
        }

        assertEquals(Set.of(0L, 1L, 2L, 3L, 4L, 5L, 20L, 21L, 22L, 23L, 24L, 25L), found(ds, s, 12));
    }

    @Test
    @DisplayName("apagar as linhas mais novas, que podem ser as únicas do nível mais alto, não esvazia a busca")
    void deletingTheNewestRowsNeverEmptiesTheSearch() {
        Session s = session();
        Datastore ds = indexed(s);
        int rows = 120;
        for (int i = 0; i < rows; i++) {
            create(ds, s, i, (i % 12) / 11.0, (i / 12) / 9.0);
        }

        // os níveis de cima têm poucos nós: apagando de trás para a frente, o mais alto esvazia mais de uma vez
        for (int i = rows - 1; i > 0; i--) {
            ds.execute("DELETE p:" + i, s, null);
            assertEquals(Math.min(10, i), found(ds, s, 10).size(), "busca depois de apagar p:" + i);
        }

        for (int i = rows; i < rows + 20; i++) {
            create(ds, s, i, (i % 12) / 11.0, (i % 7) / 9.0);
        }
        assertEquals(21, found(ds, s, 21).size(), "linhas criadas depois precisam ser alcançáveis");
    }

    @Test
    @DisplayName("depois de apagar dois terços e mover linhas, todas as vivas continuam alcançáveis")
    void survivorsStayReachableAfterHeavyDeletesAndUpdates() {
        Session s = session();
        Datastore ds = indexed(s);
        Random random = new Random(42);
        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            create(ds, s, i, coordinate(random), coordinate(random));
            ids.add(i);
        }
        Collections.shuffle(ids, random);

        for (int id : ids.subList(0, 200)) {
            ds.execute("DELETE p:" + id, s, null);
        }
        for (int id : ids.subList(200, 230)) {
            ds.execute("UPDATE p:" + id + " SET v = [" + coordinate(random) + ", " + coordinate(random) + "]",
                s, null);
        }

        Set<Long> alive = new TreeSet<>();
        ids.subList(200, 300).forEach(id -> alive.add((long) id));
        assertEquals(alive, found(ds, s, 100));
    }
}
