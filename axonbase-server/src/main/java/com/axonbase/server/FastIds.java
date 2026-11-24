package com.axonbase.server;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Identificadores de requisição e de consulta em execução. Não são segredos (só correlacionam log, atividade e
 * cancelamento, que exigem autenticação), então não precisam de {@code SecureRandom}: o {@code UUID.randomUUID()}
 * serializa todas as requisições numa trava global.
 */
final class FastIds {

    private FastIds() {
    }

    /** UUID versão 4 formatado, gerado sem trava global. */
    static String uuid() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        long hi = (r.nextLong() & 0xFFFFFFFFFFFF0FFFL) | 0x0000000000004000L;
        long lo = (r.nextLong() & 0x3FFFFFFFFFFFFFFFL) | 0x8000000000000000L;
        return new UUID(hi, lo).toString();
    }
}
