package com.axonbase.core.cluster;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.RocksDbBackend;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Portão 1 da E2: vazão de escrita de um cluster de três nós, no modo de {@code AXON_STORAGE_SYNC} (ou da
 * propriedade {@code axon.storage.sync}).
 *
 * <p>Os três nós ficam no mesmo processo e no mesmo disco, então os fsyncs dos três disputam o dispositivo. Uso:
 * {@code RaftWriteGateBenchmark <diretório> [escritores] [segundos]}, com {@code escritores} separados por
 * vírgula (padrão {@code 8,64,256}). Imprime uma linha {@code GATE1} por cenário e quantidade de escritores:
 * {@code raw} confirma lotes direto no consenso; {@code sql} faz {@code CREATE} pelo datastore.</p>
 */
public final class RaftWriteGateBenchmark {

    private static final int WARMUP_SECONDS = 5;

    private RaftWriteGateBenchmark() {
    }

    @FunctionalInterface
    private interface Write {
        void run(int thread, long sequence);
    }

    /** Vazão e latência de um cenário. */
    record Result(String mode, String scenario, int writers, double opsPerSecond, double p50Millis,
                  double p99Millis, long failed) {
        @Override
        public String toString() {
            return String.format("GATE1 mode=%s scenario=%s writers=%d ops_s=%.0f p50_ms=%.2f p99_ms=%.2f failed=%d",
                mode, scenario, writers, opsPerSecond, p50Millis, p99Millis, failed);
        }
    }

    public static void main(String[] args) throws Exception {
        int[] writers = args.length > 1 ? parseWriters(args[1]) : new int[] {8, 64, 256};
        int seconds = args.length > 2 ? Integer.parseInt(args[2]) : 20;
        run(Path.of(args[0]), writers, seconds);
        System.exit(0);
    }

    static int[] parseWriters(String list) {
        return Arrays.stream(list.split(",")).map(String::trim).mapToInt(Integer::parseInt).toArray();
    }

    /** Sobe um cluster de três nós em {@code root} e mede cada quantidade de escritores, imprimindo as linhas. */
    static List<Result> run(Path root, int[] writers, int seconds) throws Exception {
        String mode = RocksDbBackend.SyncMode.fromEnvironment().name();
        Path dir = Files.createTempDirectory(Files.createDirectories(root), "gate1-");
        List<ClusterRuntime> nodes = new ArrayList<>();
        List<RocksDbBackend> backends = new ArrayList<>();
        List<InetSocketAddress> addresses = ClusterHarness.reservePorts(3).stream()
            .map(port -> new InetSocketAddress("127.0.0.1", port)).toList();
        List<Result> results = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                InetSocketAddress self = addresses.get(i);
                RocksDbBackend backend = new RocksDbBackend(dir.resolve("db" + i).toString());
                backends.add(backend);
                nodes.add(new ClusterRuntime(new ClusterConfig("n" + i, "g", self,
                    addresses.stream().filter(a -> a != self).toList()), dir.resolve("raft" + i), backend));
            }
            List<Datastore> datastores = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                Datastore ds = new Datastore(backends.get(i));
                nodes.get(i).appliedBatchListener(ds.appliedBatchListener());
                ds.commitCoordinator(nodes.get(i), "n" + i);
                datastores.add(ds);
            }
            // Numa máquina carregada a liderança pode trocar: cada escrita procura o líder do momento.
            retry(() -> {
                int leader = nodes.indexOf(awaitLeader(nodes));
                datastores.get(leader).execute("DEFINE TABLE pedido", session(), null);
            });
            byte[] value = new byte[256];
            Arrays.fill(value, (byte) 'x');
            String text = "x".repeat(200);
            for (int count : writers) {
                results.add(measure(mode, "raw", count, seconds, (t, i) -> {
                    ClusterRuntime leader = awaitLeader(nodes);
                    leader.confirm(leader.config().nodeId(), new CommittedBatch(count + "-" + t + "-" + i,
                        Map.of("raw-" + count + "-" + t + "-" + i, value), Set.of()));
                }));
                results.add(measure(mode, "sql", count, seconds, (t, i) -> datastores
                    .get(nodes.indexOf(awaitLeader(nodes))).execute("CREATE pedido:w" + count + "t" + t + "x" + i
                        + " CONTENT { loja: " + t + ", nota: '" + text + "' }", session(), null)));
            }
        } finally {
            for (ClusterRuntime node : nodes) {
                node.close();
            }
            backends.forEach(RocksDbBackend::close);
            BenchmarkFiles.delete(dir);
        }
        return results;
    }

    private static ClusterRuntime awaitLeader(List<ClusterRuntime> nodes) {
        for (ClusterRuntime node : nodes) {
            if (node.role() == ClusterRole.LEADER) {
                return node;
            }
        }
        ClusterHarness.await("sem líder", 60_000,
            () -> nodes.stream().anyMatch(n -> n.role() == ClusterRole.LEADER && n.status().ready()));
        return nodes.stream().filter(n -> n.role() == ClusterRole.LEADER).findFirst().orElseThrow();
    }

    private static void retry(Runnable work) {
        for (int attempt = 1; ; attempt++) {
            try {
                work.run();
                return;
            } catch (RuntimeException notYet) {
                if (attempt >= 30) {
                    throw notYet;
                }
                try {
                    Thread.sleep(500);
                } catch (InterruptedException interrupted) {
                    throw new IllegalStateException(interrupted);
                }
            }
        }
    }

    private static Session session() {
        Session session = Session.create();
        session.namespace("bench");
        session.database("bench");
        return session;
    }

    private static Result measure(String mode, String scenario, int threads, int seconds, Write write)
            throws InterruptedException {
        AtomicBoolean measuring = new AtomicBoolean();
        AtomicBoolean running = new AtomicBoolean(true);
        AtomicLong failed = new AtomicLong();
        List<List<Long>> latencies = new ArrayList<>();
        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int thread = t;
            List<Long> mine = new ArrayList<>(1 << 16);
            latencies.add(mine);
            workers.add(Thread.ofPlatform().start(() -> {
                for (long i = 0; running.get(); i++) {
                    long started = System.nanoTime();
                    try {
                        write.run(thread, i);
                    } catch (RuntimeException e) {
                        if (failed.incrementAndGet() == 1) {
                            System.out.println("Exception: " + e);
                        }
                        continue;
                    }
                    if (measuring.get()) {
                        mine.add(System.nanoTime() - started);
                    }
                }
            }));
        }
        Thread.sleep(WARMUP_SECONDS * 1_000L);
        long from = System.nanoTime();
        measuring.set(true);
        Thread.sleep(seconds * 1_000L);
        measuring.set(false);
        double elapsed = (System.nanoTime() - from) / 1e9;
        running.set(false);
        for (Thread worker : workers) {
            worker.join(60_000);
        }
        long[] all = latencies.stream().flatMap(List::stream).mapToLong(Long::longValue).sorted().toArray();
        Result result = new Result(mode, scenario, threads, all.length / elapsed, percentile(all, 0.50),
            percentile(all, 0.99), failed.get());
        System.out.println(result);
        return result;
    }

    private static double percentile(long[] sorted, double p) {
        return sorted.length == 0 ? 0 : sorted[(int) Math.min(sorted.length - 1, (long) (sorted.length * p))] / 1e6;
    }
}
