package com.axonbase.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Troca da base dentro do processo (restore físico em servidor ligado)")
class RocksDbBackendSwapTest {

    @TempDir
    Path tmp;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static TreeMap<String, String> dump(RocksDbBackend b) {
        TreeMap<String, String> out = new TreeMap<>();
        b.scanEntries("", (k, v) -> {
            out.put(k, new String(v, StandardCharsets.UTF_8));
            return true;
        });
        return out;
    }

    private static void fill(RocksDbBackend b, String prefix, int n) {
        for (int i = 0; i < n; i++) {
            b.put(prefix + i, bytes(prefix + "v" + i));
        }
    }

    @Test
    @DisplayName("a base volta ao estado do backup, continua gravável e a versão nunca regride")
    void swapRevertsStateAndKeepsVersionMonotonic() {
        Path live = tmp.resolve("live");
        Path store = tmp.resolve("bk");
        try (RocksDbBackend b = new RocksDbBackend(live.toString());
             PhysicalBackups pb = PhysicalBackups.open(store)) {
            fill(b, "a", 400);
            TreeMap<String, String> atBackup = dump(b);
            int id = pb.create(b).id();
            fill(b, "depois", 400);
            b.delete("a3");
            long versionBefore = b.currentVersion();

            Path prepared = PhysicalBackups.prepareRestore(store, id, live);
            Path previous = b.replaceWith(prepared, 10_000);

            assertEquals(atBackup, dump(b));
            assertTrue(b.get("depois1").isEmpty());
            assertTrue(b.currentVersion() >= versionBefore, "a marca d'água não pode andar para trás");
            b.put("novo", bytes("x"));
            assertTrue(b.versionOf("novo") > versionBefore, "versão nova maior que as já entregues");
            assertTrue(Files.exists(previous), "a base anterior fica guardada");
            assertFalse(Files.exists(prepared), "o diretório preparado virou a base ativa");
        }
        try (RocksDbBackend reopened = new RocksDbBackend(live.toString())) {
            assertTrue(reopened.get("novo").isPresent(), "a base trocada é a que persiste ao reabrir");
        }
    }

    @Test
    @DisplayName("troca com leitores e escritores rodando não derruba nem corrompe")
    void swapUnderConcurrentLoad() throws Exception {
        Path live = tmp.resolve("live");
        Path store = tmp.resolve("bk");
        try (RocksDbBackend b = new RocksDbBackend(live.toString());
             PhysicalBackups pb = PhysicalBackups.open(store)) {
            fill(b, "base", 2000);
            TreeMap<String, String> atBackup = dump(b);
            int id = pb.create(b).id();
            fill(b, "extra", 500);

            AtomicBoolean stop = new AtomicBoolean();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            AtomicInteger ops = new AtomicInteger();
            List<Thread> threads = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                int n = t;
                threads.add(Thread.ofPlatform().start(() -> {
                    int i = 0;
                    while (!stop.get()) {
                        try {
                            b.put("w" + n + "-" + (i++ % 50), bytes("x" + i));
                            ops.incrementAndGet();
                        } catch (Throwable e) {
                            failure.compareAndSet(null, e);
                            return;
                        }
                    }
                }));
            }
            for (int t = 0; t < 3; t++) {
                threads.add(Thread.ofPlatform().start(() -> {
                    while (!stop.get()) {
                        try {
                            b.get("base7");
                            b.scanPrefix("base1", k -> true);
                            b.snapshotScan(0, (k, v, ver) -> true, null);
                            ops.incrementAndGet();
                        } catch (Throwable e) {
                            failure.compareAndSet(null, e);
                            return;
                        }
                    }
                }));
            }
            Thread.sleep(300);

            Path prepared = PhysicalBackups.prepareRestore(store, id, live);
            b.replaceWith(prepared, 15_000);
            int opsAtSwap = ops.get();
            Thread.sleep(300);
            stop.set(true);
            for (Thread th : threads) {
                th.join(10_000);
            }
            assertNull(failure.get(), () -> "operação falhou durante a troca: " + failure.get());
            assertTrue(ops.get() > opsAtSwap, "as operações retomaram depois da troca");
            TreeMap<String, String> after = dump(b);
            after.keySet().removeIf(k -> k.startsWith("w"));
            assertEquals(atBackup, after);
        }
    }

    @Test
    @DisplayName("varredura que não termina faz a troca desistir sem tocar na base")
    void swapGivesUpWhenAScanNeverEnds() throws Exception {
        Path live = tmp.resolve("live");
        Path store = tmp.resolve("bk");
        try (RocksDbBackend b = new RocksDbBackend(live.toString());
             PhysicalBackups pb = PhysicalBackups.open(store)) {
            fill(b, "a", 100);
            int id = pb.create(b).id();
            fill(b, "depois", 10);
            TreeMap<String, String> before = dump(b);

            CountDownLatch inside = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread scan = Thread.ofPlatform().start(() -> b.scanPrefix("a", k -> {
                inside.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                return false;
            }));
            assertTrue(inside.await(10, TimeUnit.SECONDS));

            Path prepared = PhysicalBackups.prepareRestore(store, id, live);
            assertThrows(RocksDbBackend.SwapTimeoutException.class, () -> b.replaceWith(prepared, 300));
            release.countDown();
            scan.join(10_000);

            assertEquals(before, dump(b), "a base original ficou como estava");
            b.put("ainda-grava", bytes("ok"));
            assertTrue(b.get("ainda-grava").isPresent(), "o portão reabriu");
        }
    }

    @Test
    @DisplayName("quem já está dentro de uma varredura consegue ler outra chave com o portão fechando")
    void reentrantReadInsideVisitorDoesNotDeadlock() throws Exception {
        Path live = tmp.resolve("live");
        Path store = tmp.resolve("bk");
        try (RocksDbBackend b = new RocksDbBackend(live.toString());
             PhysicalBackups pb = PhysicalBackups.open(store)) {
            fill(b, "a", 200);
            int id = pb.create(b).id();
            CountDownLatch inside = new CountDownLatch(1);
            AtomicBoolean ok = new AtomicBoolean();
            Thread scan = Thread.ofPlatform().start(() -> b.scanPrefix("a", k -> {
                inside.countDown();
                try {
                    Thread.sleep(400); // dá tempo de o portão começar a fechar
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                ok.set(b.get("a1").isPresent()); // leitura reentrante dentro da varredura
                return false;
            }));
            assertTrue(inside.await(10, TimeUnit.SECONDS));
            Path prepared = PhysicalBackups.prepareRestore(store, id, live);
            b.replaceWith(prepared, 15_000);
            scan.join(10_000);
            assertTrue(ok.get());
        }
    }
}
