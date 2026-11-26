package com.axonbase.core.cluster;

import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;

import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Portão 3 da E2: um nó novo entra num cluster grande a partir de um backup físico, sob escrita.
 *
 * <p>Uso: {@code RaftJoinGateBenchmark <diretório> [gigabytes] [votantes] [escritas por segundo]}. A base é
 * gravada direto no armazenamento (valores aleatórios, incompressíveis) em cada votante, e então o cluster
 * sobe. Mede o tempo do começo do backup até o learner alcançar o líder, e a maior pausa entre duas escritas
 * confirmadas nesse intervalo.</p>
 */
public final class RaftJoinGateBenchmark {

    private static final int VALUE_BYTES = 4_096;
    private static final int BASELINE_SECONDS = 30;

    private RaftJoinGateBenchmark() {
    }

    public static void main(String[] args) throws Exception {
        Path root = Files.createDirectories(Path.of(args[0]));
        double gigabytes = args.length > 1 ? Double.parseDouble(args[1]) : 10;
        int voters = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        int rate = args.length > 3 ? Integer.parseInt(args[3]) : 500;
        Path dir = Files.createTempDirectory(root, "gate3-");
        long rows = (long) (gigabytes * (1L << 30) / VALUE_BYTES);
        List<ClusterRuntime> nodes = new ArrayList<>();
        List<RocksDbBackend> backends = new ArrayList<>();
        List<InetSocketAddress> addresses = ClusterHarness.reservePorts(voters + 1).stream()
            .map(port -> new InetSocketAddress("127.0.0.1", port)).toList();
        List<InetSocketAddress> members = addresses.subList(0, voters);
        try {
            for (int i = 0; i < voters; i++) {
                RocksDbBackend backend = new RocksDbBackend(dir.resolve("db" + i).toString());
                backends.add(backend);
                long started = System.nanoTime();
                load(backend, rows);
                System.out.printf("GATE3 load node=%d rows=%d seconds=%.0f%n", i, rows, seconds(started));
            }
            for (int i = 0; i < voters; i++) {
                InetSocketAddress self = members.get(i);
                nodes.add(new ClusterRuntime(new ClusterConfig("n" + i, "g", self,
                    members.stream().filter(a -> a != self).toList()), dir.resolve("raft" + i), backends.get(i),
                    RaftOptions.defaults().withAllowedLearners(List.of("*"))));
            }
            ClusterHarness.await("sem líder", 120_000,
                () -> nodes.stream().anyMatch(n -> n.role() == ClusterRole.LEADER && n.status().ready()));
            ClusterRuntime leader = nodes.stream().filter(n -> n.role() == ClusterRole.LEADER).findFirst()
                .orElseThrow();

            Pauses pauses = new Pauses();
            AtomicBoolean writing = new AtomicBoolean(true);
            AtomicLong failed = new AtomicLong();
            Thread writer = Thread.ofPlatform().start(() -> write(leader, rate, writing, failed, pauses));
            Thread.sleep(BASELINE_SECONDS * 1_000L);
            System.out.printf("GATE3 baseline %s failed=%d%n", pauses.report(BASELINE_SECONDS), failed.get());

            pauses.reset();
            long join = System.nanoTime();
            Path backups = dir.resolve("backup");
            try (PhysicalBackups target = PhysicalBackups.open(backups)) {
                target.create((RocksDbBackend) leader.backend());
            }
            System.out.printf("GATE3 backup seconds=%.1f max_gap_ms=%.0f%n", seconds(join), pauses.maxGapMillis());
            long restoring = System.nanoTime();
            RaftApplier.Applied restored = LearnerBootstrap.restore(backups, dir.resolve("learner-db"))
                .orElseThrow();
            System.out.printf("GATE3 restore seconds=%.1f index=%d max_gap_ms=%.0f%n", seconds(restoring),
                restored.index(), pauses.maxGapMillis());
            long starting = System.nanoTime();
            RocksDbBackend learnerDb = new RocksDbBackend(dir.resolve("learner-db").toString());
            backends.add(learnerDb);
            ClusterRuntime learner = new ClusterRuntime(new ClusterConfig("r1", "g", addresses.get(voters), members,
                true), dir.resolve("learner-raft"), learnerDb);
            nodes.add(learner);
            ClusterHarness.await("learner não alcançou", 900_000, () -> !learner.leaderAddress().isEmpty()
                && leader.commitIndex() - learner.commitIndex() <= Math.max(10, rate / 10));
            double total = seconds(join);
            System.out.printf("GATE3 catchup seconds=%.1f entries=%d%n", seconds(starting),
                learner.commitIndex() - restored.index());
            System.out.printf("GATE3 join voters=%d gigabytes=%.1f total_seconds=%.1f %s failed=%d "
                    + "snapshot_bytes=%d%n", voters, gigabytes, total, pauses.report(total), failed.get(),
                leader.snapshotBytes());
            writing.set(false);
            writer.join(60_000);
            ClusterHarness.await("learner não convergiu", 120_000,
                () -> learner.commitIndex() == leader.commitIndex());
            check(leader, learner, rows);
        } finally {
            for (ClusterRuntime node : nodes) {
                node.close();
            }
            backends.forEach(RocksDbBackend::close);
            BenchmarkFiles.delete(dir);
        }
        System.exit(0);
    }

    /** Escreve em ritmo fixo, anotando cada confirmação. */
    private static void write(ClusterRuntime leader, int rate, AtomicBoolean writing, AtomicLong failed,
                              Pauses pauses) {
        byte[] value = new byte[1_024];
        long interval = 1_000_000_000L / rate;
        long next = System.nanoTime();
        for (long i = 0; writing.get(); i++) {
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            }
            next = Math.max(next + interval, System.nanoTime() - interval);
            long started = System.nanoTime();
            try {
                leader.confirm(leader.config().nodeId(),
                    new CommittedBatch("w" + i, Map.of("w" + i, value), Set.of()));
                pauses.confirmed(started);
            } catch (RuntimeException e) {
                failed.incrementAndGet();
            }
        }
    }

    private static void check(ClusterRuntime leader, ClusterRuntime learner, long rows) {
        SplittableRandom random = new SplittableRandom(7);
        int different = 0;
        for (int i = 0; i < 2_000; i++) {
            String key = key(random.nextLong(rows));
            if (!Arrays.equals(leader.backend().get(key).orElse(null), learner.backend().get(key).orElse(null))) {
                different++;
            }
        }
        System.out.printf("GATE3 check sampled=2000 different=%d%n", different);
    }

    /** Intervalos entre confirmações e latência de cada escrita. */
    private static final class Pauses {
        private final List<Long> latencies = new ArrayList<>();
        private long last = System.nanoTime();
        private long maxGap;

        synchronized void confirmed(long started) {
            long now = System.nanoTime();
            maxGap = Math.max(maxGap, now - last);
            last = now;
            latencies.add(now - started);
        }

        synchronized void reset() {
            latencies.clear();
            maxGap = 0;
            last = System.nanoTime();
        }

        synchronized double maxGapMillis() {
            return Math.max(maxGap, System.nanoTime() - last) / 1e6;
        }

        synchronized String report(double seconds) {
            long[] sorted = latencies.stream().mapToLong(Long::longValue).sorted().toArray();
            return String.format("writes_s=%.0f p99_ms=%.1f max_write_ms=%.0f max_gap_ms=%.0f",
                sorted.length / seconds,
                sorted.length == 0 ? 0 : sorted[(int) (sorted.length * 0.99)] / 1e6,
                sorted.length == 0 ? 0 : sorted[sorted.length - 1] / 1e6, maxGapMillis());
        }
    }

    private static String key(long row) {
        return "base-" + row;
    }

    private static void load(RocksDbBackend backend, long rows) {
        SplittableRandom random = new SplittableRandom(42);
        Map<String, byte[]> batch = new LinkedHashMap<>();
        for (long row = 0; row < rows; row++) {
            byte[] value = new byte[VALUE_BYTES];
            random.nextBytes(value);
            batch.put(key(row), value);
            if (batch.size() == 512 || row == rows - 1) {
                backend.applyReplicated(batch, Set.of());
                batch = new LinkedHashMap<>();
            }
        }
        backend.syncDurable();
    }

    private static double seconds(long since) {
        return (System.nanoTime() - since) / 1e9;
    }
}
