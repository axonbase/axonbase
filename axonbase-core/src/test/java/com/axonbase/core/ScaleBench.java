package com.axonbase.core;

import com.axonbase.core.engine.Datastore;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/** Ferramentas comuns do benchmark de linha de base e do teste de portão de escala. */
final class ScaleBench {

    private ScaleBench() {
    }

    static Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    /** Percentil (ms) de uma lista de latências em nanossegundos. */
    static double pct(long[] nanos, int n, double p) {
        long[] copy = Arrays.copyOf(nanos, n);
        Arrays.sort(copy);
        return copy[Math.min(n - 1, (int) Math.floor(p * n))] / 1e6;
    }

    static String order(int i) {
        String[] status = {"novo", "preparo", "entrega", "entregue"};
        return "{codigo: 'c" + i + "', cliente: 'cli" + (i % 5000) + "', loja: 'loja" + (i % 300) + "', status: '"
            + status[i % 4] + "', valor: " + (10 + i % 500) + ", itens: [{p: 'x', q: 1}, {p: 'y', q: 2}]}";
    }

    /** Roda {@code total} operações em {@code threads} threads e devolve [ops/s, p50 ms, p99 ms, max ms]. */
    static double[] run(Datastore ds, int threads, int total, BiFunction<Integer, Session, String> sql) throws Exception {
        long[] lat = new long[total];
        AtomicInteger next = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        long t0 = System.nanoTime();
        List<Future<?>> fs = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            fs.add(pool.submit(() -> {
                Session s = session();
                int i;
                while ((i = next.getAndIncrement()) < total) {
                    long a = System.nanoTime();
                    ds.execute(sql.apply(i, s), s, null);
                    lat[i] = System.nanoTime() - a;
                }
                return null;
            }));
        }
        for (var f : fs) {
            f.get();
        }
        double secs = (System.nanoTime() - t0) / 1e9;
        pool.shutdown();
        return new double[] {total / secs, pct(lat, total, 0.5), pct(lat, total, 0.99), pct(lat, total, 1.0)};
    }

    static double time(Datastore ds, String sql) {
        Session s = session();
        long a = System.nanoTime();
        ds.execute(sql, s, null);
        return (System.nanoTime() - a) / 1e6;
    }

    static void out(String fmt, Object... args) {
        System.out.println("BASE " + String.format(Locale.ROOT, fmt, args));
    }
}
