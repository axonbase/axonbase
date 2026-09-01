package com.axonbase.core.storage;

import com.axonbase.core.engine.Datastore;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Helper de retry otimista {@link Datastore#withRetry(int, java.util.function.Supplier)}:
 * rebela conflitos de versión cunha espera pequena ata esgotar as tentativas.
 */
class OptimisticRetryTest {

    @Test
    void conflitoSempreDevolveNullAoLimite() {
        Datastore ds = Datastore.memory();
        Integer result = ds.withRetry(3, () -> {
            throw new VersionConflictException("conflito persistente");
        });
        assertNull(result, "sen éxito, o helper debe voltar null ao esgotar as tentativas");
    }

    @Test
    void unConflitoEUnSuccessoSucceede() {
        Datastore ds = Datastore.memory();
        AtomicInteger attempts = new AtomicInteger();
        Integer result = ds.withRetry(5, () -> {
            if (attempts.incrementAndGet() < 2) {
                throw new VersionConflictException("primeiro intento conflita");
            }
            return 42;
        });
        assertEquals(42, result);
        assertEquals(2, attempts.get(), "debe fallar unha vez e acertar á segunda");
    }

    @Test
    void esperaConfigurableNaoBloqueaSemConflito() {
        Datastore ds = Datastore.memory();
        Integer result = ds.withRetry(3, () -> 7, 1_000L);
        assertEquals(7, result);
    }
}