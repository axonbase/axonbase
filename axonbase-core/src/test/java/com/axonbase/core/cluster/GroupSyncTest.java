package com.axonbase.core.cluster;

import com.axonbase.core.storage.RocksDbBackend.SyncMode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Group commit do log Raft")
class GroupSyncTest {

    /** Disco simulado: o que estava escrito quando o fsync começou fica durável quando ele termina. */
    private static final class Disk {
        final AtomicLong written = new AtomicLong();
        final AtomicLong flushed = new AtomicLong();
        final AtomicLong syncs = new AtomicLong();
        final AtomicBoolean broken = new AtomicBoolean();

        void fsync() {
            long target = written.get();
            syncs.incrementAndGet();
            if (broken.get()) {
                throw new IllegalStateException("disco falhou");
            }
            try {
                Thread.sleep(5);
            } catch (InterruptedException e) {
                throw new IllegalStateException(e);
            }
            flushed.accumulateAndGet(target, Math::max);
        }
    }

    @Test
    @DisplayName("com always, várias threads dividem o mesmo fsync e ninguém volta antes do seu dado")
    void concurrentWritersShareSyncs() throws Exception {
        Disk disk = new Disk();
        GroupSync group = new GroupSync(SyncMode.ALWAYS, disk.written::get, disk::fsync);
        ExecutorService pool = Executors.newFixedThreadPool(16);
        List<Future<?>> tasks = new ArrayList<>();
        for (int t = 0; t < 16; t++) {
            tasks.add(pool.submit(() -> {
                for (int i = 0; i < 20; i++) {
                    long index = disk.written.incrementAndGet();
                    assertTrue(group.awaitDurable(index));
                    assertTrue(disk.flushed.get() >= index, "voltou antes do fsync do índice " + index);
                }
            }));
        }
        for (Future<?> task : tasks) {
            task.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(320, group.durableIndex());
        assertTrue(disk.syncs.get() < 200, "320 escritas usaram " + disk.syncs.get() + " fsyncs");
    }

    @Test
    @DisplayName("com batch e off, a escrita não espera disco")
    void batchAndOffDoNotWait() {
        for (SyncMode mode : List.of(SyncMode.BATCH, SyncMode.OFF)) {
            Disk disk = new Disk();
            GroupSync group = new GroupSync(mode, disk.written::get, disk::fsync);
            disk.written.set(10);
            assertTrue(group.awaitDurable(10));
            assertEquals(0, disk.syncs.get(), mode.name());
            assertEquals(10, group.durableIndex(), mode.name());
        }
    }

    @Test
    @DisplayName("o fsync periódico só roda em batch e só quando há algo novo")
    void periodicSyncOnlyWhenDirty() {
        Disk disk = new Disk();
        GroupSync group = new GroupSync(SyncMode.BATCH, disk.written::get, disk::fsync);
        group.periodicSync();
        assertEquals(0, disk.syncs.get());
        disk.written.set(3);
        group.periodicSync();
        group.periodicSync();
        assertEquals(1, disk.syncs.get());

        Disk off = new Disk();
        GroupSync never = new GroupSync(SyncMode.OFF, off.written::get, off::fsync);
        off.written.set(3);
        never.periodicSync();
        assertEquals(0, off.syncs.get());
    }

    @Test
    @DisplayName("uma entrada truncada não é dada como durável")
    void truncatedEntryIsNotDurable() {
        Disk disk = new Disk();
        GroupSync group = new GroupSync(SyncMode.ALWAYS, disk.written::get, disk::fsync);
        disk.written.set(5);
        assertTrue(group.awaitDurable(5));
        disk.written.set(2);
        group.truncated(3);
        assertEquals(2, group.durableIndex());
        assertFalse(group.awaitDurable(5));
    }

    @Test
    @DisplayName("um fsync que falha envenena o log: nada mais é confirmado")
    void failedSyncIsFatal() {
        Disk disk = new Disk();
        GroupSync group = new GroupSync(SyncMode.ALWAYS, disk.written::get, disk::fsync);
        disk.written.set(1);
        disk.broken.set(true);
        assertThrows(RuntimeException.class, () -> group.awaitDurable(1));
        disk.broken.set(false);
        assertThrows(RuntimeException.class, () -> group.awaitDurable(1));
        assertEquals(0, group.durableIndex());
    }
}
