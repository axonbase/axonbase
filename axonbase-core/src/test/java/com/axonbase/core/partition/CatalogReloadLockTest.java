package com.axonbase.core.partition;

import com.axonbase.core.catalog.Database;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * Uma transação aberta antes da releitura do catálogo confirma com a trava que pegou do banco antigo. Ela precisa ser
 * a mesma do banco novo, ou uma mudança de partição passa a correr junto com a escrita.
 */
@DisplayName("Partições: a releitura do catálogo mantém a trava do banco")
class CatalogReloadLockTest {

    @Test
    @DisplayName("o banco recarregado usa a mesma trava de índices e a mesma época")
    void reload_keepsIndexLockAndEpoch() {
        try (TimeData db = TimeData.memory().threeDays()) {
            Database before = db.ds.findDatabase("test", "dev");
            before.bumpIndexEpoch();
            long epoch = before.indexEpoch();
            before.indexLock().readLock().lock();
            try {
                db.ds.reloadFromBackend();

                Database after = db.ds.findDatabase("test", "dev");
                assertNotSame(before, after);
                assertEquals(1, after.indexLock().getReadHoldCount());
                assertEquals(epoch, after.indexEpoch());
            } finally {
                before.indexLock().readLock().unlock();
            }
        }
    }
}
