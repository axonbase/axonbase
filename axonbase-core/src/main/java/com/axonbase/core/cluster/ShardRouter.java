package com.axonbase.core.cluster;

import com.axonbase.common.Messages;

/** Roteador de (namespace, database) para grupo Raft via hash consistente. */
public final class ShardRouter {

    private final String[] groups;

    public ShardRouter(int groupCount) {
        if (groupCount < 1) throw new IllegalArgumentException(Messages.get("cluster_group_required"));
        this.groups = new String[groupCount];
        for (int i = 0; i < groupCount; i++) {
            this.groups[i] = "group-" + i;
        }
    }

    public ShardRouter(String... groups) {
        if (groups == null || groups.length == 0) throw new IllegalArgumentException(Messages.get("cluster_group_required"));
        this.groups = groups.clone();
    }

    public int groupCount() { return groups.length; }

    public String groupFor(String namespace, String database) {
        int hash = (namespace + "/" + database).hashCode();
        int idx = Math.floorMod(hash, groups.length);
        return groups[idx];
    }

    public String groupAt(int index) {
        return groups[index];
    }
}
