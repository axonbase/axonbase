package com.axonbase.core.engine;

/**
 * Conta, por thread, as chamadas que saem do banco (link de banco remoto, provedor de embeddings, provedor de
 * IA). Um comando que fez alguma não pode ser refeito sozinho: a chamada aconteceria de novo.
 *
 * <p>Quem chama para fora registra aqui antes do envio, mesmo que o envio falhe. Expressões avaliadas em várias
 * threads são sempre puras, então a contagem da thread do comando é completa.</p>
 */
public final class ExternalCalls {

    private static final ThreadLocal<long[]> MADE = ThreadLocal.withInitial(() -> new long[1]);

    private ExternalCalls() {
    }

    public static void record() {
        MADE.get()[0]++;
    }

    /** Total de chamadas feitas por esta thread; só a diferença entre duas leituras significa algo. */
    public static long count() {
        return MADE.get()[0];
    }
}
