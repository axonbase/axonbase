package com.axonbase.core.cluster;

import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.VersionedKvBackend;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Grupo Raft embutido para uma database. O transporte é local, mas a semântica é
 * a de um log: líder único, quórum, termo, índice de commit e snapshot de
 * catch-up.
 *
 * <p>Cada membro pode receber o próprio backend e o próprio
 * {@link AppliedBatchListener}. Isso deixa exercitar a replicação de DDL e o
 * fanout de live queries num seguidor sem subir sockets.</p>
 */
public final class RaftGroup {

    private final String id;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<LogEntry> log = new ArrayList<>();
    private long term = 1;
    private long commitIndex;
    private String leader;

    private RaftGroup(String id, Map<String, VersionedKvBackend> members) {
        this.id = id;
        members.forEach((member, backend) -> nodes.put(member, new Node(member, backend)));
        elect();
    }

    public static RaftGroup inMemory(String id, String... members) {
        if (members.length == 0) {
            throw new IllegalArgumentException("grupo precisa de membros");
        }
        Map<String, VersionedKvBackend> backends = new LinkedHashMap<>();
        for (String member : members) {
            backends.put(member, new MemoryBackend());
        }
        return new RaftGroup(id, backends);
    }

    /** Grupo sobre backends já existentes, um por membro. */
    public static RaftGroup over(String id, Map<String, VersionedKvBackend> members) {
        if (members.isEmpty()) {
            throw new IllegalArgumentException("grupo precisa de membros");
        }
        return new RaftGroup(id, members);
    }

    public synchronized String leader() {
        return leader;
    }

    public synchronized long term() {
        return term;
    }

    public synchronized long commitIndex() {
        return commitIndex;
    }

    public synchronized int quorum() {
        return nodes.size() / 2 + 1;
    }

    public synchronized VersionedKvBackend backend(String node) {
        return require(node).backend;
    }

    /** Acopla o observador de batches aplicados a um membro específico. */
    public synchronized void listener(String node, AppliedBatchListener listener) {
        require(node).listener = listener == null ? AppliedBatchListener.none() : listener;
    }

    /** Confirma e aplica o batch apenas depois de verificar a maioria ativa. */
    public synchronized long append(String node, CommittedBatch batch) {
        if (!node.equals(leader)) {
            throw new NotLeaderException(leader);
        }
        if (activeCount() < quorum()) {
            throw new QuorumUnavailableException(id);
        }
        LogEntry entry = new LogEntry(++commitIndex, term, batch);
        log.add(entry);
        for (Node member : nodes.values()) {
            if (member.active) {
                RaftApplier.apply(member.backend, batch, member.listener);
            }
        }
        return entry.index;
    }

    public synchronized void stop(String node) {
        require(node).active = false;
        if (node.equals(leader)) {
            elect();
        }
    }

    public synchronized void start(String node) {
        Node recovering = require(node);
        recovering.active = true;
        if (leader == null) {
            elect();
        }
        if (leader != null && !node.equals(leader)) {
            installSnapshot(recovering, require(leader));
        }
    }

    public synchronized Status status() {
        return new Status(id, leader, term, commitIndex, activeCount(), nodes.size());
    }

    private void elect() {
        String elected = nodes.values().stream().filter(n -> n.active).map(n -> n.id)
            .sorted().findFirst().orElse(null);
        if (elected != null && !elected.equals(leader)) {
            term++;
        }
        leader = elected;
    }

    private int activeCount() {
        return (int) nodes.values().stream().filter(n -> n.active).count();
    }

    private Node require(String id) {
        Node node = nodes.get(id);
        if (node == null) {
            throw new IllegalArgumentException("nó desconhecido " + id);
        }
        return node;
    }

    /**
     * Catch-up de um membro que voltou: copia o estado do líder como um batch e
     * avisa o listener, para que o catálogo em memória do membro também se alinhe.
     */
    private void installSnapshot(Node target, Node source) {
        Map<String, byte[]> puts = new LinkedHashMap<>();
        for (String key : source.backend.keysWithPrefix("")) {
            puts.put(key, source.backend.get(key).orElseThrow());
        }
        java.util.Set<String> deletes = new java.util.LinkedHashSet<>(
            target.backend.keysWithPrefix(""));
        deletes.removeAll(puts.keySet());
        RaftApplier.apply(target.backend, new CommittedBatch("snapshot-" + commitIndex, puts, deletes),
            target.listener);
    }

    private static final class Node {
        final String id;
        final VersionedKvBackend backend;
        volatile AppliedBatchListener listener = AppliedBatchListener.none();
        boolean active = true;

        Node(String id, VersionedKvBackend backend) {
            this.id = id;
            this.backend = backend == null ? new MemoryBackend() : backend;
        }
    }

    private record LogEntry(long index, long term, CommittedBatch batch) {
    }

    public record Status(String group, String leader, long term, long commitIndex,
                         int activeMembers, int members) {
    }
}
