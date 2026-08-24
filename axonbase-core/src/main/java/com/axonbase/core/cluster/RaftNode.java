package com.axonbase.core.cluster;

import java.nio.file.Path;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.VersionedKvBackend;

/**
 * Estado durável do participante Raft e aplicação ordenada de entradas confirmadas.
 *
 * <p>Toda aplicação passa por {@link RaftApplier}, inclusive a releitura do log no
 * arranque e a instalação de snapshot. É isso que garante que o plano de controle
 * seja reconstruído tanto num seguidor ao vivo quanto num nó que acabou de subir.</p>
 */
public final class RaftNode {

    private final String nodeId;
    private final RaftNodeState state;
    private final FileRaftLog log;
    private final VersionedKvBackend stateMachine;
    private volatile AppliedBatchListener listener = AppliedBatchListener.none();

    public RaftNode(String nodeId, Path dataDir) {
        this(nodeId, dataDir, new MemoryBackend());
    }

    public RaftNode(String nodeId, Path dataDir, VersionedKvBackend stateMachine) {
        this.nodeId = nodeId;
        this.state = new RaftNodeState(dataDir);
        this.log = new FileRaftLog(dataDir);
        this.stateMachine = stateMachine;
        applyThrough(state.appliedIndex());
    }

    /**
     * Instala o observador de batches aplicados e reprocessa o que já está no log.
     *
     * <p>O reprocessamento é necessário porque o {@code Datastore} só existe depois
     * do nó: sem ele, tudo que o log aplicou antes do acoplamento ficaria invisível
     * para o catálogo em memória.</p>
     */
    public synchronized void listener(AppliedBatchListener listener) {
        this.listener = listener == null ? AppliedBatchListener.none() : listener;
        for (long i = 1; i <= state.appliedIndex(); i++) {
            FileRaftLog.Entry entry = log.entry(i);
            if (entry != null) {
                // Reaplicar é seguro: o batch é idempotente e o storage já tem os bytes.
                RaftApplier.apply(stateMachine, entry.batch(), this.listener);
            }
        }
    }

    public synchronized RaftWire.Response handle(RaftWire.Request request) {
        if (request.term() < state.term()) {
            return response(false);
        }
        if (request.type() == RaftWire.VOTE) {
            return vote(request);
        }
        if (request.type() == RaftWire.APPEND) {
            return append(request);
        }
        if (request.type() == RaftWire.SNAPSHOT) {
            return snapshot(request);
        }
        return response(false);
    }

    private RaftWire.Response vote(RaftWire.Request request) {
        if (request.term() > state.term()) {
            state.update(request.term(), "", state.appliedIndex());
        }
        RaftPayload.Vote candidate = RaftPayload.decodeVote(request.payload());
        long localTerm = log.termAt(log.lastIndex());
        boolean upToDate = candidate.lastTerm() > localTerm
            || candidate.lastTerm() == localTerm && candidate.lastIndex() >= log.lastIndex();
        boolean free = state.votedFor().isEmpty() || state.votedFor().equals(request.leaderOrCandidate());
        if (free && upToDate) {
            state.update(request.term(), request.leaderOrCandidate(), state.appliedIndex());
        }
        return response(free && upToDate);
    }

    private RaftWire.Response append(RaftWire.Request request) {
        if (request.term() > state.term()) {
            state.update(request.term(), "", state.appliedIndex());
        }
        if (request.payload().length == 0) {
            commitThrough(Math.min(request.commitIndex(), log.lastIndex()), request.term(),
                request.leaderOrCandidate());
            return response(true);
        }
        RaftPayload.Append append;
        boolean legacy = false;
        try {
            append = RaftPayload.decodeAppend(request.payload());
        } catch (IllegalArgumentException ignored) {
            legacy = true;
            append = new RaftPayload.Append(request.index() - 1, log.termAt(request.index() - 1),
                RaftPayload.decode(request.payload()));
        }
        if (append.previousIndex() != 0 && log.termAt(append.previousIndex()) != append.previousTerm()) {
            return response(false);
        }
        FileRaftLog.Entry existing = log.entry(request.index());
        if (existing != null && existing.term() != request.term()) {
            log.truncateFrom(request.index());
            existing = null;
        }
        if (existing == null) {
            if (!legacy && request.index() != log.lastIndex() + 1) {
                return response(false);
            }
            log.append(new FileRaftLog.Entry(request.index(), request.term(), append.batch()));
        }
        commitThrough(Math.min(request.commitIndex(), log.lastIndex()), request.term(),
            request.leaderOrCandidate());
        return response(true);
    }

    private RaftWire.Response snapshot(RaftWire.Request request) {
        if (request.term() > state.term()) {
            state.update(request.term(), "", state.appliedIndex());
        }
        // O payload do snapshot é um batch atômico do state machine no índice pedido.
        if (request.index() > state.appliedIndex()) {
            RaftApplier.apply(stateMachine, RaftPayload.decode(request.payload()), listener);
            state.update(request.term(), request.leaderOrCandidate(), request.index());
        }
        if (log.lastIndex() < request.index()) {
            log.truncateFrom(1);
        }
        return response(true);
    }

    synchronized void appendLocal(long term, long index, CommittedBatch batch) {
        if (log.entry(index) == null) {
            log.append(new FileRaftLog.Entry(index, term, batch));
        }
    }

    synchronized void commitLocal(long term, String leader, long index) {
        commitThrough(index, term, leader);
    }

    public synchronized long term() {
        return state.term();
    }

    public synchronized long lastIndex() {
        return log.lastIndex();
    }

    public synchronized long lastTerm() {
        return log.termAt(log.lastIndex());
    }

    public synchronized long commitIndex() {
        return state.appliedIndex();
    }

    synchronized long lastTermAt(long index) {
        return log.termAt(index);
    }

    synchronized FileRaftLog.Entry entry(long index) {
        return log.entry(index);
    }

    synchronized long beginElection(String candidate) {
        long term = state.term() + 1;
        state.update(term, candidate, state.appliedIndex());
        return term;
    }

    private void commitThrough(long index, long term, String leader) {
        for (long i = state.appliedIndex() + 1; i <= index; i++) {
            FileRaftLog.Entry entry = log.entry(i);
            if (entry != null) {
                RaftApplier.apply(stateMachine, entry.batch(), listener);
            }
        }
        if (index > state.appliedIndex()) {
            state.update(term, leader, index);
        }
    }

    private void applyThrough(long index) {
        for (long i = 1; i <= index; i++) {
            FileRaftLog.Entry entry = log.entry(i);
            if (entry != null) {
                RaftApplier.apply(stateMachine, entry.batch(), listener);
            }
        }
    }

    private RaftWire.Response response(boolean accepted) {
        return new RaftWire.Response(state.term(), accepted, log.lastIndex());
    }

    public VersionedKvBackend stateMachine() {
        return stateMachine;
    }
}
