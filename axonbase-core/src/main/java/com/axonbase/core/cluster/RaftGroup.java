package com.axonbase.core.cluster;

import com.axonbase.core.storage.MemoryBackend;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Grupo Raft embutido para uma database. O transporte é local nesta primeira
 * implementação, mas a semântica é a de um log: líder único, quórum, termo,
 * índice de commit e snapshot de catch-up.
 */
public final class RaftGroup {

    private final String id;
    private final Map<String, Node> nodes = new LinkedHashMap<>();
    private final List<LogEntry> log = new ArrayList<>();
    private long term = 1;
    private long commitIndex;
    private String leader;

    private RaftGroup(String id, String... members) {
        this.id = id;
        for (String member : members) {
            nodes.put(member, new Node(member));
        }
        elect();
    }

    public static RaftGroup inMemory(String id, String... members) {
        if (members.length == 0) throw new IllegalArgumentException("grupo precisa de membros");
        return new RaftGroup(id, members);
    }

    public synchronized String leader() { return leader; }
    public synchronized long term() { return term; }
    public synchronized long commitIndex() { return commitIndex; }
    public synchronized int quorum() { return nodes.size() / 2 + 1; }
    public synchronized MemoryBackend backend(String node) { return require(node).backend; }

    /** Confirma e aplica o batch apenas depois de verificar a maioria ativa. */
    public synchronized long append(String node, CommittedBatch batch) {
        if (!node.equals(leader)) throw new NotLeaderException(leader);
        if (activeCount() < quorum()) throw new QuorumUnavailableException(id);
        LogEntry entry = new LogEntry(++commitIndex, term, batch);
        log.add(entry);
        nodes.values().stream().filter(n -> n.active).forEach(n -> apply(n.backend, batch));
        return entry.index;
    }

    public synchronized void stop(String node) {
        require(node).active = false;
        if (node.equals(leader)) elect();
    }

    public synchronized void start(String node) {
        Node recovering = require(node);
        recovering.active = true;
        if (leader == null) elect();
        if (leader != null && !node.equals(leader)) installSnapshot(recovering, require(leader));
    }

    public synchronized Status status() {
        return new Status(id, leader, term, commitIndex, activeCount(), nodes.size());
    }

    private void elect() {
        String elected = nodes.values().stream().filter(n -> n.active).map(n -> n.id)
            .sorted().findFirst().orElse(null);
        if (elected != null && !elected.equals(leader)) term++;
        leader = elected;
    }

    private int activeCount() { return (int) nodes.values().stream().filter(n -> n.active).count(); }

    private Node require(String id) {
        Node node = nodes.get(id);
        if (node == null) throw new IllegalArgumentException("nó desconhecido " + id);
        return node;
    }

    private void installSnapshot(Node target, Node source) {
        for (String key : target.backend.keysWithPrefix("")) target.backend.delete(key);
        for (String key : source.backend.keysWithPrefix(""))
            target.backend.put(key, source.backend.get(key).orElseThrow());
        target.backend.flush();
    }

    private static void apply(MemoryBackend backend, CommittedBatch batch) {
        backend.commit(Map.of(), batch.puts(), batch.deletes());
    }

    private static final class Node {
        final String id;
        final MemoryBackend backend = new MemoryBackend();
        boolean active = true;
        Node(String id) { this.id = id; }
    }

    private record LogEntry(long index, long term, CommittedBatch batch) { }
    public record Status(String group, String leader, long term, long commitIndex,
                         int activeMembers, int members) { }
}
