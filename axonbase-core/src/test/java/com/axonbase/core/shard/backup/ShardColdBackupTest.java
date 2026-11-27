package com.axonbase.core.shard.backup;

import com.axonbase.core.engine.Datastore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DisplayName("Shards: backup com camada fria")
@Timeout(300)
class ShardColdBackupTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("o backup de shard bloqueia os objetos frios, e a coleta não os apaga depois de removida a partição")
    void shardBackupHoldsColdObjects() throws IOException {
        try (ColdFleet fleet = new ColdFleet(dir)) {
            fleet.firstDayCold();

            fleet.backups.create();

            Datastore home = fleet.home().datastore();
            assertEquals(1, home.coldTier().holds().names("physical-").size(), "só o shard base cita objetos");
            fleet.sql("DROP PARTITION ON TABLE events AT d\"" + ColdFleet.DAY1 + "\"");
            assertEquals(0, fleet.collectGarbage());
            assertEquals(1, fleet.coldObjects().size());
        }
    }
}
