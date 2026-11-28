package com.axonbase.core.qos;

import java.util.concurrent.atomic.LongAdder;

/** Histograma de duração sem travas, para comparar as classes de carga. */
final class LatencyHistogram {

    private static final double[] BOUNDS = {0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30};

    private final LongAdder[] counts = new LongAdder[BOUNDS.length + 1];
    private final LongAdder nanos = new LongAdder();

    LatencyHistogram() {
        for (int i = 0; i < counts.length; i++) {
            counts[i] = new LongAdder();
        }
    }

    void record(long duration) {
        double seconds = duration / 1e9;
        int slot = 0;
        while (slot < BOUNDS.length && seconds > BOUNDS[slot]) {
            slot++;
        }
        counts[slot].increment();
        nanos.add(duration);
    }

    QosStats.Histogram snapshot() {
        long[] cumulative = new long[BOUNDS.length];
        long total = 0;
        for (int i = 0; i < BOUNDS.length; i++) {
            total += counts[i].sum();
            cumulative[i] = total;
        }
        total += counts[BOUNDS.length].sum();
        return new QosStats.Histogram(BOUNDS.clone(), cumulative, total, nanos.sum() / 1e9);
    }
}
