package com.axonbase.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Teste de queda do portão da E1.1: um processo filho faz backup ou troca de base e o pai o mata com
 * {@code kill -9} em instantes aleatórios. Depois de cada queda a base original precisa abrir íntegra, todo
 * backup listado precisa verificar e um backup novo precisa funcionar.
 */
@DisplayName("Queda no meio de um backup e no meio de uma troca de base (kill -9)")
class PhysicalBackupCrashTest {

    @TempDir
    Path tmp;

    // ---- processo filho ----

    /** Ponto de entrada do processo filho: {@code backup <live> <store>} ou {@code swap <live> <store> <id>}. */
    public static final class Child {
        public static void main(String[] args) throws Exception {
            switch (args[0]) {
                case "backup" -> {
                    try (RocksDbBackend b = new RocksDbBackend(args[1], RocksDbBackend.SyncMode.ALWAYS);
                         PhysicalBackups pb = PhysicalBackups.open(Path.of(args[2]))) {
                        System.out.println("READY");
                        System.out.flush();
                        for (int round = 0; ; round++) {
                            for (int i = 0; i < 300; i++) {
                                b.put("r" + round + "-" + i, new byte[512]);
                            }
                            pb.create(b);
                        }
                    }
                }
                case "swap" -> {
                    Path live = Path.of(args[1]);
                    Path prepared = PhysicalBackups.prepareRestore(Path.of(args[2]), Integer.parseInt(args[3]), live);
                    DirectorySwap.afterMove = () -> {
                        try {
                            Thread.sleep(8); // alarga a janela da troca para o kill cair em qualquer fase
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                        }
                    };
                    System.out.println("READY");
                    System.out.flush();
                    DirectorySwap.swap(live, prepared);
                    System.out.println("DONE");
                    System.out.flush();
                }
                default -> throw new IllegalArgumentException(args[0]);
            }
        }
    }

    private Process spawn(String... args) throws IOException {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> cmd = new java.util.ArrayList<>(List.of(java, "-Xmx512m", "-cp",
            System.getProperty("java.class.path"), Child.class.getName()));
        cmd.addAll(List.of(args));
        return new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(
            ProcessBuilder.Redirect.to(new File(tmp.toFile(), "child-" + System.nanoTime() + ".log"))).start();
    }

    private static void waitFor(Path log, String line, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (Files.exists(log) && Files.readString(log).contains(line)) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("o filho não imprimiu " + line + " em " + timeoutMs + " ms");
    }

    private Path lastLog() throws IOException {
        try (var s = Files.list(tmp)) {
            return s.filter(p -> p.getFileName().toString().startsWith("child-"))
                .max(java.util.Comparator.comparing(p -> p.toFile().lastModified())).orElseThrow();
        }
    }

    private static TreeMap<String, String> dump(RocksDbBackend b) {
        TreeMap<String, String> out = new TreeMap<>();
        b.scanEntries("", (k, v) -> {
            out.put(k, new String(v, StandardCharsets.UTF_8));
            return true;
        });
        return out;
    }

    // ---- testes ----

    @org.junit.jupiter.api.BeforeAll
    static void nativeLibrary() {
        org.rocksdb.RocksDB.loadLibrary();
    }

    @Test
    @DisplayName("kill -9 no meio de backups: a base abre, todo backup listado verifica e um novo funciona")
    void killDuringBackups() throws Exception {
        Random random = new Random(42);
        for (int round = 0; round < 6; round++) {
            Path live = tmp.resolve("live-b" + round);
            Path store = tmp.resolve("store-b" + round);
            Process p = spawn("backup", live.toString(), store.toString());
            try {
                waitFor(lastLog(), "READY", 30_000);
                Thread.sleep(200 + random.nextInt(1500));
            } finally {
                p.destroyForcibly();
                p.waitFor(10, TimeUnit.SECONDS);
            }
            try (PhysicalBackups pb = PhysicalBackups.open(store)) {
                for (PhysicalBackups.Info info : pb.list()) {
                    assertTrue(pb.verify(info.id()), "rodada " + round + ": backup " + info.id() + " não verifica");
                }
                try (RocksDbBackend b = new RocksDbBackend(live.toString(), RocksDbBackend.SyncMode.OFF)) {
                    b.keysWithPrefix("r0-"); // a base original abre e responde
                    int before = pb.list().size();
                    PhysicalBackups.Info fresh = pb.create(b);
                    assertEquals(before + 1, pb.list().size(), "rodada " + round + ": backup novo depois da queda");
                    assertTrue(pb.verify(fresh.id()));
                }
            }
        }
    }

    @Test
    @DisplayName("kill -9 no meio da troca de base: a base fica inteira antiga ou inteira nova, nunca misturada")
    void killDuringSwap() throws Exception {
        Random random = new Random(7);
        int sawOld = 0;
        int sawNew = 0;
        for (int round = 0; round < 24; round++) {
            Path live = tmp.resolve("live-s" + round);
            Path store = tmp.resolve("store-s" + round);
            TreeMap<String, String> oldState;
            TreeMap<String, String> newState;
            int id;
            try (RocksDbBackend b = new RocksDbBackend(live.toString(), RocksDbBackend.SyncMode.OFF);
                 PhysicalBackups pb = PhysicalBackups.open(store)) {
                for (int i = 0; i < 4000; i++) {
                    b.put("novo-" + i, ("n" + i).getBytes(StandardCharsets.UTF_8));
                }
                newState = dump(b);
                id = pb.create(b).id();
                for (int i = 0; i < 4000; i++) {
                    b.delete("novo-" + i);
                }
                for (int i = 0; i < 3000; i++) {
                    b.put("antigo-" + i, ("a" + i).getBytes(StandardCharsets.UTF_8));
                }
                oldState = dump(b);
            }
            Process p = spawn("swap", live.toString(), store.toString(), String.valueOf(id));
            try {
                waitFor(lastLog(), "READY", 30_000);
                Thread.sleep(random.nextInt(400));
            } finally {
                p.destroyForcibly();
                p.waitFor(10, TimeUnit.SECONDS);
            }
            try (RocksDbBackend after = new RocksDbBackend(live.toString(), RocksDbBackend.SyncMode.OFF)) {
                TreeMap<String, String> state = dump(after);
                if (state.equals(oldState)) {
                    sawOld++;
                } else if (state.equals(newState)) {
                    sawNew++;
                } else {
                    throw new AssertionError("rodada " + round + ": a base ficou misturada ("
                        + state.size() + " chaves; antiga " + oldState.size() + ", nova " + newState.size() + ")");
                }
            }
        }
        System.out.println("GATE QUEDA troca: " + sawOld + " vezes base antiga, " + sawNew + " vezes base nova, 0 misturadas");
        assertEquals(24, sawOld + sawNew);
    }
}
