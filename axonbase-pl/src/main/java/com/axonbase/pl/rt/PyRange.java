package com.axonbase.pl.rt;

/** Objeto range (preguicoso). */
public final class PyRange {
    public final long start;
    public final long stop;
    public final long step;

    public PyRange(long start, long stop, long step) {
        this.start = start;
        this.stop = stop;
        this.step = step;
    }

    /** Quantidade de elementos. */
    public long size() {
        if (step > 0) {
            return start >= stop ? 0 : (stop - start - 1) / step + 1;
        }
        return start <= stop ? 0 : (start - stop - 1) / (-step) + 1;
    }

    /** Elemento no indice i (0 ate size-1). */
    public long at(long i) {
        return start + i * step;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof PyRange r)) {
            return false;
        }
        long a = size();
        return a == r.size() && (a == 0 || (start == r.start && (a == 1 || step == r.step)));
    }

    @Override
    public int hashCode() {
        return Long.hashCode(size() == 0 ? 0 : start * 31 + step);
    }
}
