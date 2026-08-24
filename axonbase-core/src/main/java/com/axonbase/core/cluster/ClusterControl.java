package com.axonbase.core.cluster;

import java.util.LinkedHashMap;
import java.util.Map;

/** Grupo de controle e shard map inicial, com uma réplica Raft por database. */
public final class ClusterControl {

    private final String clusterId;
    private final Map<String, RaftGroup> groups = new LinkedHashMap<>();
    private final String[] defaultMembers;

    public ClusterControl(String clusterId, String... members) {
        this.clusterId = clusterId;
        this.defaultMembers = members.clone();
    }

    public synchronized RaftGroup createDatabaseGroup(String namespace, String database, String... members) {
        String id = groupId(namespace, database);
        String[] assigned = members.length == 0 ? defaultMembers : members;
        return groups.computeIfAbsent(id, ignored -> RaftGroup.inMemory(id, assigned));
    }

    public synchronized String groupId(String namespace, String database) {
        return namespace + "/" + database;
    }

    public synchronized String leader(String namespace, String database) {
        return group(namespace, database).leader();
    }

    public synchronized long append(String namespace, String database, String node, CommittedBatch batch) {
        return group(namespace, database).append(node, batch);
    }

    public synchronized RaftGroup group(String namespace, String database) {
        RaftGroup group = groups.get(groupId(namespace, database));
        if (group == null) throw new IllegalArgumentException("database sem grupo: " + namespace + "/" + database);
        return group;
    }

    public String clusterId() { return clusterId; }
}
