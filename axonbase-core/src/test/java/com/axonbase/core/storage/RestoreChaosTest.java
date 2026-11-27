package com.axonbase.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code kill -9} no meio de uma restauração sobre uma base existente: ao reabrir, a base precisa estar inteira, a
 * antiga ou a restaurada, nunca uma mistura. Aceita {@code -Daxon.chaos.seed} e {@code -Daxon.chaos.rounds}.
 */
@DisplayName("Caos: queda durante a restauração")
@Timeout(600)
class RestoreChaosTest {

    private static final long SEED = Long.getLong("axon.chaos.seed", 20261126L);
    private static final int ROUNDS = Math.max(1, Integer.getInteger("axon.chaos.rounds", 1));
    private static final int KEYS = 2_000;
    private static final int VALUE_BYTES = 8 * 1024;

    @TempDir
    Path dir;

    private static byte[] value(String generation, int key) {
        byte[] value = new byte[VALUE_BYTES];
        new Random(31L * key + generation.hashCode()).nextBytes(value);
        return value;
    }

    private static void fill(Path data, String generation) {
        try (RocksDbBackend db = new RocksDbBackend(data.toString())) {
            for (int i = 0; i < KEYS; i++) {
                db.put("k" + i, value(generation, i));
            }
            db.put("generation", generation.getBytes(StandardCharsets.UTF_8));
        }
    }

    /** A geração presente, depois de conferir que todas as chaves são dela. */
    private static String wholeGeneration(Path data) {
        try (RocksDbBackend db = new RocksDbBackend(data.toString())) {
            String generation = new String(db.get("generation").orElseThrow(
                () -> new AssertionError("base sem marca de geração: " + data)), StandardCharsets.UTF_8);
            for (int i = 0; i < KEYS; i++) {
                int key = i;
                org.junit.jupiter.api.Assertions.assertArrayEquals(value(generation, i),
                    db.get("k" + i).orElseThrow(() -> new AssertionError("falta k" + key + " em " + generation)),
                    "k" + i + " não é da geração " + generation);
            }
            return generation;
        }
    }

    private Process restore(Path backups, Path data, long slowMillis) throws IOException {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-Xmx256m",
            "-cp", System.getProperty("java.class.path"), RestoreChild.class.getName(), backups.toString(),
            data.toString(), Long.toString(slowMillis))
            .redirectError(ProcessBuilder.Redirect.appendTo(dir.resolve("restore.err").toFile())).start();
    }

    /** Lê até a linha esperada; devolve {@code false} se a saída acabar antes. */
    private static boolean readUntil(BufferedReader out, String line) throws IOException {
        for (String read = out.readLine(); read != null; read = out.readLine()) {
            if (read.equals(line)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("a base fica inteira, a antiga ou a restaurada, seja qual for o instante da queda")
    void killedRestoreLeavesAWholeBase() throws Exception {
        Path backups = dir.resolve("backups");
        Path source = dir.resolve("origem");
        fill(source, "nova");
        try (RocksDbBackend db = new RocksDbBackend(source.toString());
             PhysicalBackups engine = PhysicalBackups.open(backups)) {
            engine.create(db);
        }
        // mede uma restauração inteira para espalhar as quedas por toda a duração dela
        Path timed = dir.resolve("medida");
        fill(timed, "velha");
        long slow = 15;
        long full;
        Process first = restore(backups, timed, slow);
        try (var out = new BufferedReader(new InputStreamReader(first.getInputStream(), StandardCharsets.UTF_8))) {
            assertTrue(readUntil(out, "RESTORE start"));
            long started = System.nanoTime();
            assertTrue(readUntil(out, "RESTORE done"), "a restauração não terminou");
            full = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        }
        assertTrue(first.waitFor(60, TimeUnit.SECONDS));
        assertEquals("nova", wholeGeneration(timed));

        Random random = new Random(SEED);
        int interrupted = 0;
        for (int round = 1; round <= ROUNDS * 12; round++) {
            Path data = dir.resolve("base-" + round);
            fill(data, "velha");
            long delay = random.nextLong(full + 1);
            Process process = restore(backups, data, slow);
            boolean done;
            try (var out = new BufferedReader(
                new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                assertTrue(readUntil(out, "RESTORE start"));
                done = process.waitFor(delay, TimeUnit.MILLISECONDS);
                process.destroyForcibly();
                assertTrue(process.waitFor(20, TimeUnit.SECONDS));
            }
            if (!done) {
                interrupted++;
            }

            String found = wholeGeneration(data);

            System.out.println("CHAOS seed=" + SEED + " scenario=kill-during-restore round=" + round
                + " after_ms=" + delay + " finished=" + done + " base=" + found + " PASS");
            // e uma nova tentativa completa
            Process retry = restore(backups, data, 0);
            assertTrue(retry.waitFor(120, TimeUnit.SECONDS));
            assertEquals(0, retry.exitValue());
            assertEquals("nova", wholeGeneration(data));
        }
        assertTrue(interrupted > 0, "nenhuma restauração foi interrompida: o teste não exercitou a queda");
    }
}
