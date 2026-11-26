package com.axonbase.core.cluster;

import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Passagem planejada de liderança, para parar um nó sem erro visível ao cliente.
 *
 * <p>O líder segura as escritas novas, espera as que estão em curso, segura as propostas no log, espera o alvo ter
 * tudo e manda a ele o pedido de eleição imediata ({@link RaftWire#TIMEOUT_NOW}). As escritas seguradas recebem
 * {@link NotLeaderException} sem terem sido executadas. Se algo não acontece no prazo, o líder solta tudo e continua
 * no cargo.</p>
 */
final class LeadershipTransfer {

    /** Um votante que pode receber o cargo. */
    record Candidate(String node, InetSocketAddress address, long matchIndex) {
    }

    /** O que a transferência precisa do nó. */
    interface Leader {
        boolean leading();

        /** O líder conhecido, ou vazio durante uma eleição. */
        String leader();

        NotLeaderException notLeader();

        /** Votantes que respondem e entendem o pedido de eleição imediata. */
        List<Candidate> candidates();

        long lastIndex();

        long appliedIndex();

        long matchIndex(Candidate candidate);

        /** Quem esperava uma escrita até {@code index} já recebeu a resposta. */
        boolean settled(long index);

        boolean sendTimeoutNow(Candidate candidate);
    }

    private static final long POLL_MILLIS = 2;
    /** Quanto uma escrita segurada espera, no máximo: mais que o prazo normal de uma transferência. */
    private static final long ADMIT_TIMEOUT_MILLIS = 15_000;

    private final Object stateLock;
    private final Leader node;
    /** Escritas de clientes em curso; só sob o monitor deste objeto. */
    private int writers;
    private boolean closed;
    private boolean running;
    /** Escrita só sob {@code stateLock}: quem a lê ali decide se pode propor. */
    private volatile boolean proposalsBlocked;
    private volatile boolean abstaining;
    /** Uma escrita que dispara outra na mesma thread não pode esperar pela própria vez. */
    private final ThreadLocal<int[]> depth = ThreadLocal.withInitial(() -> new int[1]);

    LeadershipTransfer(Object stateLock, Leader node) {
        this.stateLock = stateLock;
        this.node = node;
    }

    /** Este nó não disputa eleições enquanto estiver sendo drenado. Continua votando. */
    boolean abstaining() {
        return abstaining;
    }

    void abstain(boolean value) {
        this.abstaining = value;
    }

    // ---- escritas dos clientes ----

    /**
     * Admite uma escrita. Durante uma transferência, segura até ela terminar.
     *
     * @throws NotLeaderException se, depois da espera, este nó não lidera mais: nada foi executado
     */
    CommitCoordinator.WritePermit admit() {
        int[] nested = depth.get();
        if (nested[0] == 0) {
            enter();
        }
        nested[0]++;
        return new CommitCoordinator.WritePermit() {
            private boolean released;

            @Override
            public void close() {
                if (released) {
                    return;
                }
                released = true;
                if (--nested[0] == 0) {
                    leave();
                }
            }
        };
    }

    private void enter() {
        boolean waited = false;
        synchronized (this) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ADMIT_TIMEOUT_MILLIS);
            while (closed) {
                waited = true;
                if (System.nanoTime() - deadline > 0 || !pause()) {
                    throw node.notLeader();
                }
            }
            // na mesma trava da conferência: daqui em diante a transferência conta esta escrita
            writers++;
        }
        if (waited && !node.leading()) {
            leave();
            throw node.notLeader();
        }
    }

    private synchronized void leave() {
        writers--;
        notifyAll();
    }

    private synchronized int writers() {
        return writers;
    }

    /** Espera um aviso, com a trava deste objeto; {@code false} se a thread foi interrompida. */
    private boolean pause() {
        try {
            wait(POLL_MILLIS * 5);
            return true;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    // ---- propostas no log ----

    boolean blocksProposals() {
        return proposalsBlocked;
    }

    /** Espera as propostas serem liberadas. Quem chama confere de novo sob a trava de estado. */
    void awaitProposals() {
        if (!proposalsBlocked) {
            return;
        }
        synchronized (this) {
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ADMIT_TIMEOUT_MILLIS);
            while (proposalsBlocked) {
                if (System.nanoTime() - deadline > 0 || !pause()) {
                    throw node.notLeader();
                }
            }
        }
    }

    private void blockProposals(boolean value) {
        synchronized (stateLock) {
            proposalsBlocked = value;
        }
        synchronized (this) {
            notifyAll();
        }
    }

    // ---- transferência ----

    /**
     * Passa o cargo.
     *
     * @param target nó que deve assumir (identificador, ou endereço {@code host:porta} do Raft), ou {@code null}
     *               para o mais adiantado
     */
    TransferResult transfer(String target, long timeoutMillis) {
        long started = System.nanoTime();
        synchronized (this) {
            if (running) {
                return TransferResult.refused("já há uma transferência em curso", started);
            }
            running = true;
        }
        try {
            return run(target, started, started + TimeUnit.MILLISECONDS.toNanos(timeoutMillis));
        } finally {
            blockProposals(false);
            synchronized (this) {
                closed = false;
                running = false;
                notifyAll();
            }
        }
    }

    private TransferResult run(String target, long started, long deadline) {
        if (!node.leading()) {
            return TransferResult.refused("este nó não é o líder", started);
        }
        Optional<Candidate> chosen = node.candidates().stream()
            .filter(c -> target == null || target.isBlank() || c.node().equals(target)
                || RaftWire.advertise(c.address()).equals(target))
            .max(Comparator.comparingLong(Candidate::matchIndex));
        if (chosen.isEmpty()) {
            return TransferResult.refused(target == null || target.isBlank()
                ? "nenhum membro compatível está respondendo"
                : "o membro " + target + " não responde ou não aceita transferência", started);
        }
        Candidate candidate = chosen.get();
        synchronized (this) {
            closed = true;
        }
        if (!await(deadline, () -> writers() == 0)) {
            return TransferResult.refused("as escritas em curso não terminaram no prazo", started);
        }
        blockProposals(true);
        long last = node.lastIndex();
        if (!await(deadline, () -> !node.leading()
            || node.appliedIndex() >= last && node.matchIndex(candidate) >= last && node.settled(last))) {
            return TransferResult.refused("o membro " + candidate.node() + " não alcançou o líder no prazo", started);
        }
        if (!node.leading()) {
            return TransferResult.refused("o nó deixou de liderar antes da transferência", started);
        }
        if (!node.sendTimeoutNow(candidate)) {
            return TransferResult.refused("o membro " + candidate.node() + " recusou o pedido de eleição", started);
        }
        if (!await(deadline, () -> !node.leading() && !node.leader().isEmpty())) {
            return TransferResult.refused("o membro " + candidate.node() + " não assumiu no prazo", started);
        }
        return TransferResult.done(node.leader(), started);
    }

    private static boolean await(long deadline, BooleanSupplier condition) {
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() - deadline > 0) {
                return false;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
