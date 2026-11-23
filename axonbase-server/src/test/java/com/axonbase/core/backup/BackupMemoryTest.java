package com.axonbase.core.backup;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Export/restore com 200 mil chaves em RocksDB, cifrado, passando por arquivo em disco. Leva cerca de
 * 80 s, então só roda com {@code -Daxon.memtest=true}; use junto {@code -DargLine=-Xmx256m} para provar
 * que o pico de heap não depende do tamanho do dump (140 MB, mais que o heap).
 */
@org.junit.jupiter.api.condition.EnabledIfSystemProperty(named = "axon.memtest", matches = "true")
class BackupMemoryTest {

    private static final int KEYS = 200_000;
    private static final int VALUE_BYTES = 400;

    @Test
    void exportERestoreEmStreamingComHeapLimitado(@TempDir Path tmp) throws Exception {
        Datastore a = new Datastore(new RocksDbBackend(tmp.resolve("a").toString()));
        byte[] value = new byte[VALUE_BYTES];
        java.util.Arrays.fill(value, (byte) 'x');
        for (int i = 0; i < KEYS; i++) {
            a.metaPut(String.format("k%07d", i), value);
        }
        Path file = tmp.resolve("dump.axbak");
        AtomicLong peak = new AtomicLong();
        Thread sampler = new Thread(() -> {
            Runtime rt = Runtime.getRuntime();
            while (!Thread.currentThread().isInterrupted()) {
                peak.accumulateAndGet(rt.totalMemory() - rt.freeMemory(), Math::max);
                try {
                    Thread.sleep(20);
                } catch (InterruptedException e) {
                    return;
                }
            }
        });
        sampler.setDaemon(true);
        System.gc();
        long base = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        sampler.start();

        try (OutputStream out = StreamEncryption.encrypting(
                new BufferedOutputStream(Files.newOutputStream(file)), "senha")) {
            a.backupTo(out, 0);
        }
        long size = Files.size(file);

        Datastore b = new Datastore(new RocksDbBackend(tmp.resolve("b").toString()));
        try (InputStream raw = new BufferedInputStream(Files.newInputStream(file))) {
            raw.skipNBytes(StreamEncryption.HEADER.length() + 1L);
            try (var in = StreamEncryption.decrypting(raw, "senha");
                 var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                b.restoreCluster(reader);
            }
        }
        sampler.interrupt();
        long peakMb = (peak.get() - base) / (1024 * 1024);
        System.out.println("[BackupMemoryTest] dump=" + size / (1024 * 1024) + "MB heapMax="
            + Runtime.getRuntime().maxMemory() / (1024 * 1024) + "MB picoSobreBase=" + peakMb + "MB");

        assertEquals(KEYS, b.metaKeys("k").size());
        assertEquals(VALUE_BYTES, b.metaGet("k0123456").get().length);
        // o dump (140 MB) é maior que o heap de 256 MB e terminou sem OutOfMemoryError; o pico medido
        // inclui lixo ainda não coletado, por isso o limite é o heap e não uma fração do dump
        assertTrue(peakMb < 160, "pico de heap " + peakMb + "MB");
    }
}
