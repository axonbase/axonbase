package com.axonbase.core.shard;

import java.util.List;

/** Um shard: o identificador e os endereços HTTP (HyperText Transfer Protocol) dos nós do grupo. */
public record ShardInfo(String id, List<String> endpoints) {

    public ShardInfo {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("shard sem identificador");
        }
        endpoints = List.copyOf(endpoints);
    }
}
