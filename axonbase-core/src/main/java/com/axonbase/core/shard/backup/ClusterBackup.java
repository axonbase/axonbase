package com.axonbase.core.shard.backup;

import com.axonbase.core.cluster.NotLeaderException;
import com.axonbase.core.cluster.QuorumUnavailableException;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.shard.ShardException;
import com.axonbase.core.shard.ShardInfo;
import com.axonbase.core.shard.ShardMap;
import com.axonbase.core.shard.ShardMapStore;
import com.axonbase.core.shard.ShardMessages;
import com.axonbase.core.shard.ShardUnreachableException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * O coordenador do backup do cluster. A ordem é o que dá a consistência: primeiro o backup físico de cada shard,
 * depois a barreira do corte, depois o fechamento dos journals. Assim nenhum backup contém algo posterior ao corte,
 * e o journal completa cada um até ele.
 */
public final class ClusterBackup {

    private final ShardMapStore store;
    private final Function<ShardInfo, List<BackupMember>> members;
    private final Duration timeout;

    public ClusterBackup(ShardMapStore store, Function<ShardInfo, List<BackupMember>> members, Duration timeout) {
        this.store = store;
        this.members = members;
        this.timeout = timeout;
    }

    static ShardException unavailable(String message, Object... args) {
        return new ShardException(ShardException.Code.UNAVAILABLE, ShardMessages.get(message, args));
    }

    /**
     * Uma barreira em todos os shards. Chame periodicamente: é ela que prova que um instante está completo num shard
     * sem escritas.
     *
     * @return o corte: o maior relógio entre os shards. Todo lote confirmado depois da barreira passa dele
     */
    public HlcTimestamp barrier() {
        return barriers(store.require()).cut();
    }

    private record Barriers(HlcTimestamp cut, Map<String, BackupMember.Barrier> byShard) {
    }

    private Barriers barriers(ShardMap map) {
        HlcTimestamp cut = HlcTimestamp.NONE;
        for (ShardInfo shard : map.shards()) {
            cut = HlcTimestamp.max(cut, onLeader(shard, member -> member.status().clock()));
        }
        HlcTimestamp at = cut;
        Map<String, BackupMember.Barrier> byShard = new LinkedHashMap<>();
        for (ShardInfo shard : map.shards()) {
            byShard.put(shard.id(), onLeader(shard, member -> member.barrier(at)));
        }
        return new Barriers(cut, byShard);
    }

    /** Backup físico de cada shard e o manifesto com o corte. Recusado durante uma movimentação. */
    public ClusterManifest create() {
        ShardMap map = store.require();
        if (map.migration().isPresent()) {
            throw new ShardException(ShardException.Code.MOVING, ShardMessages.get("shard_migration_running"));
        }
        // garante a encarnação de cada shard e um ponto do log que o backup precisa conter
        Barriers first = barriers(map);
        Map<String, BackupMember.Copy> copies = new LinkedHashMap<>();
        for (ShardInfo shard : map.shards()) {
            copies.put(shard.id(), copy(shard, first.byShard().get(shard.id()).index()));
        }
        Barriers cut = barriers(map);
        List<ClusterManifest.ShardBackup> shards = new java.util.ArrayList<>();
        for (ShardInfo shard : map.shards()) {
            BackupMember.Copy copy = copies.get(shard.id());
            BackupMember.Barrier barrier = cut.byShard().get(shard.id());
            member(shard, copy.node()).seal(copy.baseIndex(), barrier.index());
            shards.add(new ClusterManifest.ShardBackup(shard.id(), copy.node(), copy.backupDir(), copy.backupId(),
                copy.baseIndex(), barrier.index(), copy.journalDir(), copy.incarnation()));
        }
        if (store.require().epoch() != map.epoch()) {
            throw unavailable("backup_map_changed");
        }
        return new ClusterManifest(UUID.randomUUID().toString(), cut.cut(), System.currentTimeMillis(), map, shards);
    }

    /**
     * Fecha o journal dos nós que o manifesto usa, até uma barreira nova: o que veio depois do backup passa a ser
     * alcançável pela restauração.
     */
    public HlcTimestamp sealJournals(ClusterManifest manifest) {
        Barriers now = barriers(manifest.map());
        for (ClusterManifest.ShardBackup backup : manifest.shards()) {
            ShardInfo shard = manifest.map().shards().stream().filter(s -> s.id().equals(backup.shard())).findFirst()
                .orElseThrow();
            member(shard, backup.node()).seal(backup.baseIndex(), now.byShard().get(backup.shard()).index());
        }
        return now.cut();
    }

    /** O backup de um seguidor, para não atrasar as escritas; sem seguidor que sirva, o do líder. */
    private BackupMember.Copy copy(ShardInfo shard, long minIndex) {
        List<BackupMember> candidates = new java.util.ArrayList<>(members.apply(shard));
        candidates.sort(java.util.Comparator.comparing(member -> leads(member)));
        RuntimeException last = null;
        for (BackupMember member : candidates) {
            try {
                return member.backup(minIndex);
            } catch (ShardException | ShardUnreachableException failed) {
                last = failed;
            }
        }
        throw unavailable("backup_no_member", shard.id(), last == null ? "-" : last.getMessage());
    }

    private static Boolean leads(BackupMember member) {
        try {
            return member.status().leader();
        } catch (RuntimeException unreachable) {
            return true;
        }
    }

    private BackupMember member(ShardInfo shard, String node) {
        return members.apply(shard).stream().filter(m -> m.node().equals(node)).findFirst()
            .orElseThrow(() -> unavailable("backup_no_member", shard.id(), node));
    }

    /** Executa no líder do shard, procurando-o de novo enquanto houver troca, até o prazo. */
    private <T> T onLeader(ShardInfo shard, Function<BackupMember, T> work) {
        long deadline = System.nanoTime() + timeout.toNanos();
        RuntimeException last = null;
        while (System.nanoTime() - deadline < 0) {
            for (BackupMember member : members.apply(shard)) {
                try {
                    if (member.status().leader()) {
                        return work.apply(member);
                    }
                } catch (NotLeaderException | QuorumUnavailableException | ShardUnreachableException retry) {
                    // repetir uma barreira é seguro: no pior caso sobra uma entrada a mais no log
                    last = retry;
                }
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw last != null ? unavailable("backup_no_member", shard.id(), last.getMessage())
            : unavailable("backup_no_leader", shard.id());
    }
}
