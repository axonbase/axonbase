package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.backup.S3SyncManager;
import com.axonbase.core.backup.S3SyncManagerTest.InMemoryBackupStore;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.partition.ColdTier;
import com.axonbase.core.partition.PartitionMaintenance;
import com.axonbase.core.partition.cold.FileSystemColdStore;
import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Subida do servidor: restauração do S3")
class S3StartupRestoreTest {

    private static final String BACKUP = "cluster-20000000-030301.axbak";

    @TempDir
    Path dir;

    private final MemoryBackend backend = new MemoryBackend();
    private final Datastore ds = new Datastore(backend);

    private static S3SyncManager manager(Datastore datastore, InMemoryBackupStore store, String password) {
        return new S3SyncManager(datastore, store, "bucket", "", 60_000L, 10, password, () -> 0L,
            () -> "20000000-030301");
    }

    /** Um balde com o backup de uma base que tem um registro. */
    private static InMemoryBackupStore withBackup(String password) {
        InMemoryBackupStore store = new InMemoryBackupStore();
        Datastore source = Datastore.memory();
        source.createDatabase("test", "main");
        source.execute("CREATE pessoa:ana SET nome = 'Ana'", new Session("test", "main"), null);
        assertTrue(manager(source, store, password).backupNow());
        return store;
    }

    private static int people(Datastore datastore) {
        return datastore.execute("SELECT * FROM pessoa", new Session("test", "main"), null).asArray().size();
    }

    private IllegalStateException refused(InMemoryBackupStore store, String password) {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> Main.restoreAtStartup(ds, backend, manager(ds, store, password)));
        assertTrue(refused.getMessage().contains("S3"), refused.getMessage());
        return refused;
    }

    private void assertStillEmpty() {
        assertEquals(List.of(), backend.keysWithPrefix(""), "a base precisa continuar vazia para a próxima subida");
    }

    @Test
    @DisplayName("falha ao listar o balde recusa a subida: não se sabe se há backup")
    void listingFailureRefuses() {
        InMemoryBackupStore store = new InMemoryBackupStore() {
            @Override
            public List<String> listObjects(String bucket, String prefix) {
                throw new IllegalStateException("balde fora do ar");
            }
        };

        refused(store, "");

        assertStillEmpty();
    }

    @Test
    @DisplayName("senha de cifra errada recusa a subida")
    void wrongPasswordRefuses() {
        refused(withBackup("certa"), "errada");

        assertStillEmpty();
    }

    @Test
    @DisplayName("um backup listado que não pode ser baixado recusa a subida")
    void missingListedObjectRefuses() {
        InMemoryBackupStore full = withBackup("");
        InMemoryBackupStore store = new InMemoryBackupStore() {
            @Override
            public List<String> listObjects(String bucket, String prefix) {
                return full.listObjects(bucket, prefix);
            }
        };

        refused(store, "");

        assertStillEmpty();
    }

    @Test
    @DisplayName("base com partição fria sem a camada fria configurada recusa a subida")
    void coldReferenceWithoutColdTierRefuses() {
        InMemoryBackupStore store = new InMemoryBackupStore();
        try (ColdTier cold = ColdTier.of(new FileSystemColdStore(dir.resolve("cold")), dir.resolve("cache"),
                ColdTier.Settings.defaults())) {
            Datastore source = new Datastore(new MemoryBackend(), cold);
            source.createDatabase("test", "main");
            Session session = new Session("test", "main");
            source.execute("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d", session, null);
            source.execute("CREATE events:a CONTENT {at: d\"2026-11-01T10:00:00Z\"}", session, null);
            source.execute("ALTER PARTITION ON TABLE events AT d\"2026-11-01T10:00:00Z\" TO COLD", session, null);
            PartitionMaintenance maintenance = new PartitionMaintenance(source,
                PartitionMaintenance.Options.defaults().withTtlPause(Duration.ZERO), System::currentTimeMillis);
            assertEquals(1, maintenance.runOnce().movedCold());
            assertTrue(manager(source, store, "").backupNow());
        }

        IllegalStateException refused = refused(store, "");

        assertTrue(refused.getMessage().contains("AXON_COLD_DIR"), refused.getMessage());
        assertStillEmpty();
    }

    @Test
    @DisplayName("uma restauração que parou no meio não deixa a próxima subida servir a base pela metade")
    void interruptedRestoreRefusesAgain() {
        InMemoryBackupStore good = withBackup("");
        InMemoryBackupStore broken = new InMemoryBackupStore();
        byte[] whole = good.download("bucket", BACKUP);
        byte[] bad = "chave-ruim\t###\n".getBytes(StandardCharsets.UTF_8);
        byte[] content = java.util.Arrays.copyOf(whole, whole.length + bad.length);
        System.arraycopy(bad, 0, content, whole.length, bad.length);
        broken.upload("bucket", BACKUP, new java.io.ByteArrayInputStream(content));

        refused(broken, "");

        // o processo sobe de novo sobre a mesma base, agora com um backup bom: ela continua pela metade
        Datastore restarted = new Datastore(backend);
        assertThrows(IllegalStateException.class,
            () -> Main.restoreAtStartup(restarted, backend, manager(restarted, good, "")));
    }

    @Test
    @DisplayName("balde sem backup é a primeira subida: o servidor cria os bancos iniciais")
    void emptyBucketStartsWithDefaults() {
        Main.restoreAtStartup(ds, backend, manager(ds, new InMemoryBackupStore(), ""));

        assertNotNull(ds.findDatabase("axonbase", "main"));
        assertNotNull(ds.findDatabase("app", "main"));
    }

    @Test
    @DisplayName("com backup no balde, a base vazia é restaurada")
    void restoresBackup() {
        Main.restoreAtStartup(ds, backend, manager(ds, withBackup(""), ""));

        assertEquals(1, people(ds));
    }

    @Test
    @DisplayName("base que já tem dados não é restaurada, mesmo com o balde fora do ar")
    void nonEmptyBaseIgnoresS3() {
        ds.createDatabase("test", "main");
        ds.execute("CREATE pessoa:bia SET nome = 'Bia'", new Session("test", "main"), null);
        InMemoryBackupStore down = new InMemoryBackupStore() {
            @Override
            public List<String> listObjects(String bucket, String prefix) {
                throw new IllegalStateException("balde fora do ar");
            }
        };

        Main.restoreAtStartup(ds, backend, manager(ds, down, ""));

        assertEquals(1, people(ds));
    }
}
