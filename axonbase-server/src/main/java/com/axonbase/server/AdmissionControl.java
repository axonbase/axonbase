package com.axonbase.server;

import java.util.concurrent.Semaphore;

/**
 * Limita quantas requisições HTTP de dados ficam em andamento, para que consultas lentas
 * não ocupem todas as threads do Jetty e deixem DML, DDL e as sondas de saúde sem atendimento.
 *
 * <p>Caminhos operacionais ({@code /health}, {@code /ready}, {@code /status}, {@code /version},
 * {@code /metrics}, {@code /admin}) e o upgrade de WebSocket ficam isentos. O que excede o limite
 * recebe recusa imediata em vez de esperar na fila.</p>
 */
public final class AdmissionControl {

    private static final String[] EXEMPT = {
        "/health", "/ready", "/status", "/version", "/metrics", "/admin", "/rpc/ws"
    };

    private final Semaphore permits;
    private final int limit;

    /**
     * @param httpThreads tamanho do pool HTTP
     * @return limite de requisições de dados, deixando ao menos 2 threads (ou 1/8 do pool) livres
     */
    public static int limitFor(int httpThreads) {
        int reserve = Math.max(2, httpThreads / 8);
        return Math.max(1, httpThreads - reserve);
    }

    public AdmissionControl(int limit) {
        this.limit = Math.max(1, limit);
        this.permits = new Semaphore(this.limit);
    }

    public int limit() {
        return limit;
    }

    public int inFlight() {
        return limit - permits.availablePermits();
    }

    /** {@code true} para caminhos que nunca são recusados nem contados. */
    public static boolean isExempt(String path) {
        if (path == null) {
            return true;
        }
        for (String prefix : EXEMPT) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /** Tenta entrar. Quem recebe {@code true} precisa chamar {@link #exit()}. */
    public boolean tryEnter() {
        return permits.tryAcquire();
    }

    public void exit() {
        permits.release();
    }
}
