package com.axonbase.core.pl;

import java.util.concurrent.atomic.LongAdder;

/** Contadores das chamadas AxonPL (viram as métricas {@code axe_pl_*}). */
public final class PlStats {

    private final LongAdder calls = new LongAdder();
    private final LongAdder errors = new LongAdder();
    private final LongAdder limitErrors = new LongAdder();
    private final LongAdder nanos = new LongAdder();
    private final LongAdder steps = new LongAdder();

    void call(long elapsedNanos, long stepCount) {
        calls.increment();
        nanos.add(elapsedNanos);
        steps.add(stepCount);
    }

    void error(boolean limit) {
        errors.increment();
        if (limit) {
            limitErrors.increment();
        }
    }

    public long calls() {
        return calls.sum();
    }

    public long errors() {
        return errors.sum();
    }

    public long limitErrors() {
        return limitErrors.sum();
    }

    public double seconds() {
        return nanos.sum() / 1_000_000_000.0;
    }

    public long steps() {
        return steps.sum();
    }
}
