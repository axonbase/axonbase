package com.axonbase.core.cluster;

import java.nio.file.Path;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.VersionedKvBackend;

/** Nó Raft persistente atendido pelo {@link TcpRaftServer}. */
public final class RaftNode {
    private final String nodeId;
    private final RaftNodeState state;
    private final FileRaftLog log;
    private final VersionedKvBackend stateMachine;

    public RaftNode(String nodeId, Path dataDir) {
        this(nodeId, dataDir, new MemoryBackend());
    }

    public RaftNode(String nodeId, Path dataDir, VersionedKvBackend stateMachine) {
        this.nodeId = nodeId;
        this.state = new RaftNodeState(dataDir);
        this.log = new FileRaftLog(dataDir);
        this.stateMachine = stateMachine;
        for (FileRaftLog.Entry entry : log.entries()) {
            if (entry.index() <= state.appliedIndex()) {
                RaftApplier.apply(stateMachine, entry.batch());
            }
        }
    }

    public synchronized RaftWire.Response handle(RaftWire.Request request) {
        if (request.term() < state.term()) return new RaftWire.Response(state.term(), false, state.appliedIndex());
        if (request.type() == RaftWire.VOTE) {
            if (request.term() > state.term()) state.update(request.term(), "", state.appliedIndex());
            boolean free = state.votedFor().isEmpty() || state.votedFor().equals(request.leaderOrCandidate());
            if (free) state.update(request.term(), request.leaderOrCandidate(), state.appliedIndex());
            return new RaftWire.Response(state.term(), free, state.appliedIndex());
        }
        if (request.type() == RaftWire.APPEND) {
            if (request.term() > state.term()) state.update(request.term(), request.leaderOrCandidate(), state.appliedIndex());
            FileRaftLog.Entry entry = log.entry(request.index());
            if (entry == null) {
                if (log.lastIndex() != 0 && request.index() != log.lastIndex() + 1) {
                    return new RaftWire.Response(state.term(), false, log.lastIndex());
                }
                entry = new FileRaftLog.Entry(request.index(), request.term(), RaftPayload.decode(request.payload()));
                log.append(entry);
            }
            if (request.commitIndex() >= entry.index() && entry.index() > state.appliedIndex()) {
                RaftApplier.apply(stateMachine, entry.batch());
                state.update(request.term(), request.leaderOrCandidate(), entry.index());
            }
            return new RaftWire.Response(state.term(), true, entry.index());
        }
        return new RaftWire.Response(state.term(), false, state.appliedIndex());
    }
    public VersionedKvBackend stateMachine(){return stateMachine;}
}
