package com.axonbase.core.shard.backup;

import com.axonbase.core.cluster.ClusterRole;
import com.axonbase.core.cluster.ClusterRuntime;
import com.axonbase.core.cluster.CommittedBatch;
import com.axonbase.core.cluster.RaftApplier;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** As operações de backup num nó de shard, sobre o runtime de consenso, o armazenamento e o journal dele. */
public final class ShardBackupNode implements BackupMember {

    /** Identificador da história do shard, replicado junto com os dados. */
    static final String INCARNATION_KEY = "__shard/incarnation";

    private final ClusterRuntime cluster;
    private final RocksDbBackend backend;
    private final ShardJournalHooks journal;
    private final Path backupDir;
    private final Duration timeout;

    public ShardBackupNode(ClusterRuntime cluster, RocksDbBackend backend, ShardJournalHooks journal, Path backupDir,
                           Duration timeout) {
        this.cluster = cluster;
        this.backend = backend;
        this.journal = journal;
        this.backupDir = backupDir;
        this.timeout = timeout;
    }

    @Override
    public String node() {
        return cluster.config().nodeId();
    }

    @Override
    public Status status() {
        return new Status(cluster.role() == ClusterRole.LEADER, backend.clock().now());
    }

    @Override
    public synchronized Barrier barrier(HlcTimestamp atLeast) {
        if (cluster.role() != ClusterRole.LEADER) {
            throw cluster.notLeader();
        }
        // a entrada é carimbada pelo líder depois disto, então o carimbo dela passa do pedido
        backend.clock().receive(atLeast);
        Optional<String> known = incarnation();
        String incarnation = known.orElseGet(() -> UUID.randomUUID().toString());
        Map<String, byte[]> puts = new LinkedHashMap<>();
        byte[] value = incarnation.getBytes(StandardCharsets.UTF_8);
        puts.put(ShardJournalHooks.BARRIER_KEY, value);
        if (known.isEmpty()) {
            puts.put(INCARNATION_KEY, value);
        }
        long index = cluster.confirm(node(),
            new CommittedBatch("barrier-" + UUID.randomUUID(), puts, Set.of()));
        return new Barrier(index, journal.awaitBarrier(index, timeout), incarnation);
    }

    @Override
    public synchronized Copy backup(long minIndex) {
        long deadline = System.nanoTime() + timeout.toNanos();
        long applied = appliedIndex();
        while (applied < minIndex) {
            if (System.nanoTime() - deadline > 0) {
                throw ClusterBackup.unavailable("backup_lagging", node(), minIndex);
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw ClusterBackup.unavailable("backup_lagging", node(), minIndex);
            }
            applied = appliedIndex();
        }
        String incarnation = incarnation().orElseThrow(
            () -> ClusterBackup.unavailable("backup_lagging", node(), minIndex));
        try (PhysicalBackups backups = PhysicalBackups.open(backupDir)) {
            // lido antes de copiar: a base contém pelo menos até este índice
            PhysicalBackups.Info info = backups.create(backend);
            return new Copy(node(), backupDir.toAbsolutePath().toString(), info.id(), applied,
                journal.directory().toAbsolutePath().toString(), incarnation);
        }
    }

    @Override
    public void seal(long baseIndex, long upToIndex) {
        journal.awaitIndex(upToIndex, timeout);
        journal.seal();
        ClusterRestore.requireContiguous(journal.directory(), baseIndex, upToIndex);
    }

    private long appliedIndex() {
        return RaftApplier.applied(backend).map(RaftApplier.Applied::index).orElse(0L);
    }

    private Optional<String> incarnation() {
        return backend.get(INCARNATION_KEY).map(raw -> new String(raw, StandardCharsets.UTF_8));
    }
}
