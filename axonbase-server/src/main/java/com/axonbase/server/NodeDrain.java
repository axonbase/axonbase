package com.axonbase.server;

import com.axonbase.core.cluster.TransferResult;

import java.time.Duration;

/**
 * Drena um nó antes de pará-lo: deixa de se declarar pronto, passa a liderança se a tiver, deixa de disputar
 * eleições, espera o balanceador perceber e as requisições de dados terminarem.
 */
final class NodeDrain {

    enum State {
        /** Atendendo normalmente. */
        ACTIVE,
        /** Saindo: o {@code /ready} responde 503. */
        DRAINING,
        /** Pode ser parado sem erro visível. */
        DRAINED,
        /** Não deu para sair sem erro; {@link #DRAINING} continua valendo para o {@code /ready}. */
        FAILED
    }

    /** O que a drenagem precisa do nó. */
    interface Node {
        /** Há consenso e este nó lidera. */
        boolean leading();

        TransferResult transfer(Duration timeout);

        void abstain(boolean value);

        long dataRequestsInFlight();
    }

    /** @param leader o líder que assumiu, quando este nó passou o cargo */
    record Status(State state, String leader, String reason, long millis) {
    }

    private static final long POLL_MILLIS = 20;
    private static final Duration TRANSFER_ATTEMPT = Duration.ofSeconds(5);

    private final Node node;
    private final long graceMillis;
    private final long timeoutMillis;
    private volatile Status status = new Status(State.ACTIVE, "", "", 0);
    private Thread worker;

    NodeDrain(Node node, long graceMillis, long timeoutMillis) {
        this.node = node;
        this.graceMillis = Math.max(0, graceMillis);
        this.timeoutMillis = Math.max(1, timeoutMillis);
    }

    Status status() {
        return status;
    }

    /** O {@code /ready} deve recusar. */
    boolean draining() {
        return status.state() != State.ACTIVE;
    }

    /** Começa a drenar em segundo plano. Não faz nada se já está drenando ou drenado. */
    synchronized Status start() {
        if (status.state() == State.ACTIVE || status.state() == State.FAILED) {
            status = new Status(State.DRAINING, "", "", 0);
            // aqui, sob a mesma trava do undrain: antes de sair, para o nó não voltar a se eleger
            node.abstain(true);
            worker =Thread.ofPlatform().name("axon-drain").daemon(true).start(this::run);
        }
        return status;
    }

    /** Drena e espera o fim: o desligamento normal usa este caminho. */
    Status drainAndWait() {
        start();
        Thread current;
        synchronized (this) {
            current = worker;
        }
        if (current != null) {
            try {
                current.join(timeoutMillis + graceMillis + TRANSFER_ATTEMPT.toMillis());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        return status;
    }

    /** Volta a atender. */
    synchronized Status undrain() {
        if (worker != null) {
            worker.interrupt();
            worker = null;
        }
        status = new Status(State.ACTIVE, "", "", 0);
        node.abstain(false);
        return status;
    }

    private void run() {
        long started = System.nanoTime();
        long deadline = started + timeoutMillis * 1_000_000L;
        String leader = "";
        try {
            do {
                while (node.leading()) {
                    TransferResult result = node.transfer(TRANSFER_ATTEMPT);
                    if (result.transferred()) {
                        leader = result.leader();
                        break;
                    }
                    if (System.nanoTime() - deadline > 0) {
                        finish(State.FAILED, leader, "a liderança não foi transferida: " + result.reason(), started);
                        return;
                    }
                    Thread.sleep(POLL_MILLIS * 10);
                }
                Thread.sleep(graceMillis);
                while (node.dataRequestsInFlight() > 0) {
                    if (System.nanoTime() - deadline > 0) {
                        finish(State.FAILED, leader, node.dataRequestsInFlight() + " requisições ainda em curso",
                            started);
                        return;
                    }
                    Thread.sleep(POLL_MILLIS);
                }
                // o cargo pode ter chegado durante a espera: passa de novo antes de liberar a parada
            } while (node.leading());
            finish(State.DRAINED, leader, "", started);
        } catch (InterruptedException undone) {
            // desfeito por undrain, que já registrou o estado
        } catch (RuntimeException failure) {
            finish(State.FAILED, leader, String.valueOf(failure.getMessage()), started);
        }
    }

    private synchronized void finish(State state, String leader, String reason, long startedNanos) {
        if (worker != Thread.currentThread()) {
            return;
        }
        status = new Status(state, leader, reason, (System.nanoTime() - startedNanos) / 1_000_000L);
        AxonLog.log(state == State.DRAINED ? "info" : "warn", "node_drain", "drenagem terminou",
            java.util.Map.of("state", state.name(), "leader", leader, "reason", reason, "ms", status.millis()));
    }
}
