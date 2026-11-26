package com.axonbase.core.engine;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.locks.ReentrantLock;

/** Travas por faixa de chave, sempre adquiridas em ordem crescente para não haver deadlock. */
final class KeyLocks {

    private static final int STRIPES = 1_024;
    private final ReentrantLock[] stripes = new ReentrantLock[STRIPES];

    KeyLocks() {
        for (int i = 0; i < STRIPES; i++) {
            stripes[i] = new ReentrantLock();
        }
    }

    /** Travas adquiridas; fechar solta todas. */
    interface Held extends AutoCloseable {
        @Override
        void close();
    }

    Held lock(Collection<String> keys) {
        TreeSet<Integer> indexes = new TreeSet<>();
        for (String key : keys) {
            indexes.add(Math.floorMod(key.hashCode(), STRIPES));
        }
        List<ReentrantLock> held = new ArrayList<>(indexes.size());
        for (int index : indexes) {
            stripes[index].lock();
            held.add(stripes[index]);
        }
        return () -> {
            for (int i = held.size() - 1; i >= 0; i--) {
                held.get(i).unlock();
            }
        };
    }
}
