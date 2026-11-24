package com.axonbase.core.engine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Guarda os {@code k} menores elementos de um fluxo (segundo o comparador) num heap de tamanho {@code k}, em vez de
 * ordenar tudo. O desempate é a ordem de chegada, então o resultado é o mesmo de uma ordenação estável do fluxo
 * inteiro seguida de um corte nos {@code k} primeiros.
 */
final class TopK<T> {

    private record Slot<T>(T item, long seq) {
    }

    private final int k;
    private final Comparator<Slot<T>> order;
    /** O pior elemento guardado fica na cabeça, pronto para ser trocado. */
    private final PriorityQueue<Slot<T>> heap;
    private long next;

    TopK(int k, Comparator<T> cmp) {
        this.k = k;
        this.order = (a, b) -> {
            int c = cmp.compare(a.item(), b.item());
            return c != 0 ? c : Long.compare(a.seq(), b.seq());
        };
        this.heap = new PriorityQueue<>(Math.max(1, Math.min(k, 1024)), order.reversed());
    }

    void offer(T item) {
        Slot<T> slot = new Slot<>(item, next++);
        if (heap.size() < k) {
            heap.add(slot);
        } else if (order.compare(slot, heap.peek()) < 0) {
            heap.poll();
            heap.add(slot);
        }
    }

    /** Os elementos guardados do melhor para o pior. */
    List<T> sorted() {
        List<Slot<T>> all = new ArrayList<>(heap);
        all.sort(order);
        List<T> out = new ArrayList<>(all.size());
        for (Slot<T> s : all) {
            out.add(s.item());
        }
        return out;
    }
}
