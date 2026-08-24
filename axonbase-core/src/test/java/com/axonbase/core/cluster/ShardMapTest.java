package com.axonbase.core.cluster;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShardMapTest {

    @Test
    void atribuiDatabaseAGrupoERoteiaParaOLider() {
        ClusterControl control = new ClusterControl("cluster-a", "n1", "n2", "n3");
        control.createDatabaseGroup("app", "main", "n1", "n2", "n3");

        assertEquals("app/main", control.groupId("app", "main"));
        assertEquals("n1", control.leader("app", "main"));
        assertThrows(NotLeaderException.class, () -> control.append("app", "main", "n2",
            new CommittedBatch("tx", java.util.Map.of(), java.util.Set.of())));
    }
}
