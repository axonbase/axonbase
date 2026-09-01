package com.axonbase.core.cluster;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ShardRouterTest {

    @Test
    void atribuiMesmoGrupoParaMesmoNamespaceDatabase() {
        var router = new ShardRouter(4);
        String g1 = router.groupFor("ns1", "db1");
        String g2 = router.groupFor("ns1", "db1");
        assertEquals(g1, g2);
    }

    @Test
    void distribuiPorHashEntreGrupos() {
        var router = new ShardRouter("alpha", "beta", "gamma");
        assertEquals(3, router.groupCount());
        String a = router.groupFor("app", "users");
        String b = router.groupFor("app", "orders");
        // Mesmo namespace/database sempre no mesmo grupo
        assertEquals(a, router.groupFor("app", "users"));
        // (ns,db) diferentes podem cair em grupos diferentes ou iguais
        assertNotNull(router.groupAt(0));
        assertNotNull(router.groupAt(1));
        assertNotNull(router.groupAt(2));
    }

    @Test
    void rejeitaMenosDeUmGrupo() {
        assertThrows(IllegalArgumentException.class, () -> new ShardRouter(0));
        assertThrows(IllegalArgumentException.class, () -> new ShardRouter());
    }

    @Test
    void floorModParaHashNegativo() {
        var router = new ShardRouter(3);
        // hashCode() pode ser negativo; floorMod garante índice válido
        String g = router.groupFor("some-ns", "some-db");
        assertTrue(g.startsWith("group-"));
    }
}