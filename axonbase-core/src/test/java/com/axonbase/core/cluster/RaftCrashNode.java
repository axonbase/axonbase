package com.axonbase.core.cluster;

import com.axonbase.core.storage.RocksDbBackend;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Um nó de cluster num processo próprio, para os testes de queda e de caos. Enquanto lidera, escreve chaves únicas
 * e imprime {@code ACK <chave>} depois que cada uma foi confirmada; a cada 50 ms imprime o seu estado. Lê
 * {@code stop} (para de escrever), {@code load <n>} (grava n entradas grandes se lidera), {@code backup <dir>} (tira
 * um backup físico, imprimindo {@code BACKUP start} e {@code BACKUP done}) e {@code exit} da entrada padrão.
 *
 * <p>Argumentos: id, diretório, porta, portas dos outros membros separadas por vírgula, modo de sync,
 * número de escritores.</p>
 */
public final class RaftCrashNode {

    private RaftCrashNode() {
    }

    public static void main(String[] args) throws Exception {
        String id = args[0];
        Path dir = Path.of(args[1]);
        InetSocketAddress self = new InetSocketAddress("127.0.0.1", Integer.parseInt(args[2]));
        List<InetSocketAddress> peers = new ArrayList<>();
        for (String port : args[3].split(",")) {
            peers.add(new InetSocketAddress("127.0.0.1", Integer.parseInt(port)));
        }
        RocksDbBackend.SyncMode sync = RocksDbBackend.SyncMode.valueOf(args[4]);
        int writers = Integer.parseInt(args[5]);
        // Segmentos pequenos e retenção curta: as quedas também caem durante rotação e compactação do log.
        RaftOptions options = RaftOptions.defaults().withSync(sync).withSegmentBytes(32 * 1024)
            .withRetainEntries(2_000).withRetainMillis(0);
        try (RocksDbBackend backend = new RocksDbBackend(dir.resolve("db").toString(), sync);
             ClusterRuntime node = new ClusterRuntime(new ClusterConfig(id, "g", self, peers),
                 dir.resolve("raft"), backend, options)) {
            AtomicBoolean writing = new AtomicBoolean(true);
            AtomicBoolean running = new AtomicBoolean(true);
            AtomicLong sequence = new AtomicLong();
            String life = Long.toString(ProcessHandle.current().pid());
            for (int w = 0; w < writers; w++) {
                Thread.ofPlatform().daemon().start(() -> {
                    while (running.get()) {
                        if (!writing.get() || node.role() != ClusterRole.LEADER) {
                            sleep(10);
                            continue;
                        }
                        String key = id + "-" + life + "-" + sequence.incrementAndGet();
                        try {
                            node.confirm(id, ClusterHarness.put(key, key));
                            say("ACK " + key);
                        } catch (RuntimeException notConfirmed) {
                            sleep(10);
                        }
                    }
                });
            }
            Thread.ofPlatform().daemon().start(() -> {
                while (running.get()) {
                    say("STATE " + node.role() + " " + node.commitIndex() + " " + node.lastIndex());
                    sleep(50);
                }
            });
            say("UP");
            BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            for (String line = in.readLine(); line != null && !line.equals("exit"); line = in.readLine()) {
                if (line.equals("stop")) {
                    writing.set(false);
                } else if (line.startsWith("load ")) {
                    load(node, id, Integer.parseInt(line.substring(5)));
                } else if (line.startsWith("backup ")) {
                    Path target = Path.of(line.substring(7));
                    Thread.ofPlatform().daemon().start(() -> backup(backend, target));
                }
            }
            running.set(false);
        }
        System.exit(0);
    }

    /** Entradas de 256 KiB, para um backup demorar o bastante para ser interrompido. */
    private static void load(ClusterRuntime node, String id, int entries) {
        java.util.Random random = new java.util.Random(entries);
        for (int i = 0; i < entries; i++) {
            byte[] value = new byte[256 * 1024];
            random.nextBytes(value);
            try {
                node.confirm(id, new CommittedBatch("load-" + i, java.util.Map.of("big-" + i, value),
                    java.util.Set.of()));
            } catch (RuntimeException notLeading) {
                return;
            }
        }
        say("LOADED " + entries);
    }

    private static void backup(RocksDbBackend backend, Path target) {
        try (com.axonbase.core.storage.PhysicalBackups backups =
                 com.axonbase.core.storage.PhysicalBackups.open(target)) {
            say("BACKUP start");
            say("BACKUP done " + backups.create(backend).id());
        } catch (RuntimeException failure) {
            say("BACKUP failed " + failure);
        }
    }

    private static synchronized void say(String line) {
        System.out.println(line);
        System.out.flush();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
