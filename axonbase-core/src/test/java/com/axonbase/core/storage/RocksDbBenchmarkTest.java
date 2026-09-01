package com.axonbase.core.storage;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RocksDbBenchmarkTest {

    private static final int WARMUP = 1_000;
    private static final int RECORDS = 10_000;
    private static final int BATCH_SIZE = 1_000;

    @Test
    void bulkInsertEPointLookup1M() {
        Path dir = Path.of("/tmp/axon-bench-rocksdb");
        deleteDir(dir);
        dir.toFile().mkdirs();

        RocksDbBackend b = new RocksDbBackend(dir.toString());
        try {
            benchmarkWarmup(b);

            long insertStart = System.currentTimeMillis();
            int batches = 0;
            for (int i = 0; i < RECORDS; i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, RECORDS);
                java.util.Map<String, byte[]> puts = new java.util.TreeMap<>();
                for (int j = i; j < end; j++) {
                    String key = keyFor(j);
                    puts.put(key, valueFor(j));
                }
                b.commit(java.util.Map.of(), puts, java.util.Set.of());
                batches++;
            }
            long insertEnd = System.currentTimeMillis();
            long insertMs = insertEnd - insertStart;
            double insertRate = (double) RECORDS / insertMs * 1000;

            System.out.println();
            System.out.println("=== RocksDB Benchmark (" + RECORDS + " records) ===");
            System.out.println("Bulk insert:        " + RECORDS + " records in " + insertMs + " ms");
            System.out.println("Throughput:         " + String.format("%.0f", insertRate) + " rec/s");

            long getStart = System.currentTimeMillis();
            int getOps = RECORDS;
            for (int i = 0; i < getOps; i++) {
                String key = keyFor(i);
                byte[] val = b.get(key).orElse(null);
                if (val == null) {
                    throw new AssertionError("key not found: " + key);
                }
            }
            long getEnd = System.currentTimeMillis();
            long getMs = getEnd - getStart;
            double getRate = (double) getOps / getMs * 1000;
            System.out.println("Sequential lookups:  " + getOps + " gets in " + getMs + " ms");
            System.out.println("Throughput:         " + String.format("%.0f", getRate) + " rec/s");

            Random rng = new Random(42);
            long randStart = System.currentTimeMillis();
            int randOps = RECORDS;
            for (int i = 0; i < randOps; i++) {
                int idx = rng.nextInt(RECORDS);
                String key = keyFor(idx);
                byte[] val = b.get(key).orElse(null);
                if (val == null) {
                    throw new AssertionError("key not found: " + key);
                }
            }
            long randEnd = System.currentTimeMillis();
            long randMs = randEnd - randStart;
            double randRate = (double) randOps / randMs * 1000;
            System.out.println("Random lookups:      " + randOps + " gets in " + randMs + " ms");
            System.out.println("Throughput:         " + String.format("%.0f", randRate) + " rec/s");

            long fullScanStart = System.currentTimeMillis();
            List<String> all = b.keysWithPrefix("");
            long fullScanEnd = System.currentTimeMillis();
            long fullScanMs = fullScanEnd - fullScanStart;
            System.out.println("Full scan (\"\"):     " + all.size() + " keys in " + fullScanMs + " ms");

            long sizeBytes = dirSize(dir);
            double sizeMb = sizeBytes / (1024.0 * 1024.0);
            double bytesPerRecord = (double) sizeBytes / RECORDS;
            System.out.println("DB size on disk:    " + String.format("%.1f", sizeMb) + " MB");
            System.out.println("Bytes per record:   " + String.format("%.1f", bytesPerRecord) + " B");
            System.out.println("==============================");

        } finally {
            b.close();
            deleteDir(dir);
        }
    }

    private void benchmarkWarmup(RocksDbBackend b) {
        java.util.Map<String, byte[]> puts = new java.util.TreeMap<>();
        for (int j = 0; j < WARMUP; j++) {
            puts.put("warmup:" + j, ("warmup-value-" + j).getBytes(StandardCharsets.UTF_8));
        }
        b.commit(java.util.Map.of(), puts, java.util.Set.of());
        for (int i = 0; i < WARMUP; i++) {
            b.delete("warmup:" + i);
        }
    }

    private static String keyFor(int i) {
        return String.format("doc:%09d", i);
    }

    private static byte[] valueFor(int i) {
        return ("{\"id\":" + i + ",\"name\":\"user-" + i
            + "\",\"email\":\"user" + i + "@example.com\","
            + "\"age\":" + (20 + (i % 50))
            + ",\"city\":\"city-" + (i % 100)
            + ",\"created\":\"2026-01-01T00:00:00Z\"}")
            .getBytes(StandardCharsets.UTF_8);
    }

    private static long dirSize(Path dir) {
        try {
            return java.nio.file.Files.walk(dir)
                .filter(p -> p.toFile().isFile())
                .mapToLong(p -> p.toFile().length())
                .sum();
        } catch (Exception e) {
            return -1;
        }
    }

    private static void deleteDir(Path dir) {
        if (dir.toFile().exists()) {
            try {
                java.nio.file.Files.walk(dir)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
            } catch (Exception ignored) {
            }
        }
    }
}