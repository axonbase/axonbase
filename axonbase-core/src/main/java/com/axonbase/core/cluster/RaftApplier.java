package com.axonbase.core.cluster;
import com.axonbase.core.storage.VersionedKvBackend;
import java.util.Map;
/** Aplica uma entrada Raft confirmada ao storage usando batch atômico. */
public final class RaftApplier { private RaftApplier(){} public static void apply(VersionedKvBackend backend,CommittedBatch batch){backend.commit(Map.of(),batch.puts(),batch.deletes());} }
