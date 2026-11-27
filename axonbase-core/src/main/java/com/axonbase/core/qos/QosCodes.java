package com.axonbase.core.qos;

/** Códigos de erro do QoS no protocolo (-32010 a -32014 são de cluster e cancelamento). */
public final class QosCodes {

    /** Sem vaga para uma consulta analítica: nada foi executado e repetir é seguro. */
    public static final int REJECTED = -32015;
    /** Uma consulta analítica passou do tempo máximo configurado. */
    public static final int BUDGET_EXCEEDED = -32016;

    private QosCodes() {
    }
}
