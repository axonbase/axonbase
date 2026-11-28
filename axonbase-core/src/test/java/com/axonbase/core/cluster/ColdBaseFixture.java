package com.axonbase.core.cluster;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.partition.ColdTier;
import com.axonbase.core.partition.PartitionMaintenance;
import com.axonbase.core.partition.cold.FileSystemColdStore;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** O backup físico de um nó de cluster cuja base tem uma partição na camada fria. */
public final class ColdBaseFixture {

    /** O ponto do log em que o backup foi tirado. */
    public static final long INDEX = 5;

    private ColdBaseFixture() {
    }

    /** A camada fria guardada em {@code root}, com o cache do processo {@code process}. */
    public static ColdTier tier(Path root, String process) {
        return ColdTier.of(new FileSystemColdStore(root.resolve("cold")), root.resolve("cache-" + process),
            ColdTier.Settings.defaults());
    }

    /** @return o diretório de backup, com o backup de número 1 */
    public static Path backup(Path root) {
        Path backups = root.resolve("backups");
        try (ColdTier cold = tier(root, "origem");
             RocksDbBackend source = new RocksDbBackend(root.resolve("origem").toString());
             PhysicalBackups engine = PhysicalBackups.open(backups)) {
            Datastore datastore = new Datastore(source, cold);
            datastore.createDatabase("n", "d");
            Session session = new Session("n", "d");
            datastore.execute("DEFINE PARTITION ON TABLE events FIELD at INTERVAL 1d", session, null);
            datastore.execute("CREATE events:a CONTENT {at: d\"2026-11-01T10:00:00Z\", who: \"ana\"}", session, null);
            datastore.execute("ALTER PARTITION ON TABLE events AT d\"2026-11-01T10:00:00Z\" TO COLD", session, null);
            PartitionMaintenance maintenance = new PartitionMaintenance(datastore,
                PartitionMaintenance.Options.defaults().withTtlPause(Duration.ZERO), System::currentTimeMillis);
            assertEquals(1, maintenance.runOnce().movedCold());
            source.applyReplicated(Map.of(RaftApplier.APPLIED_KEY, RaftApplier.marker(INDEX, 3)), Set.of());
            engine.create(source);
        }
        return backups;
    }

    /** Apaga os objetos frios, como se o armazenamento os tivesse perdido. */
    public static void loseColdObjects(Path root) {
        try (Stream<Path> walk = Files.walk(root.resolve("cold").resolve("parts"))) {
            for (Path file : walk.filter(Files::isRegularFile).toList()) {
                Files.delete(file);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
