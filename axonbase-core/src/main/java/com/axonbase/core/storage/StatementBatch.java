package com.axonbase.core.storage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Mini-transação implícita de um statement: acumula as escritas (registro, índices, arestas) numa camada em
 * memória sobre o backend e as grava de uma vez num único lote atômico em {@link #commit()}. As leituras veem
 * as escritas ainda não gravadas (ler o que acabou de escrever), então a checagem de UNIQUE e os índices que
 * leem o próprio estado continuam corretos.
 *
 * <p>Ao contrário de {@link Transaction}, não registra versões lidas nem prefixos varridos: é uma escrita
 * cega, como era cada {@code put} solto antes. O que muda é só a atomicidade (tudo ou nada) e o custo (uma
 * gravação no log por statement, não uma por chave). Não é thread-safe; vive dentro de um statement.</p>
 */
public final class StatementBatch implements KvBackend {

    private final VersionedKvBackend base;
    private final TreeMap<String, byte[]> staged = new TreeMap<>();
    private final Set<String> deleted = new HashSet<>();
    private final List<Runnable> afterCommit = new ArrayList<>();
    /** Versões que precisam continuar iguais no commit; vazio no caso comum, de escrita cega. */
    private final Map<String, Long> expected = new java.util.concurrent.ConcurrentHashMap<>();

    public StatementBatch(VersionedKvBackend base) {
        this.base = base;
    }

    /** Backend sobre o qual esta camada grava. */
    public VersionedKvBackend base() {
        return base;
    }

    /**
     * Exige que a chave continue como está agora: o commit falha com conflito se outra escrita a mudar antes. Chame
     * antes de ler a chave. Vale a primeira versão registrada.
     */
    public void expect(String key) {
        expected.computeIfAbsent(key, base::versionOf);
    }

    /** Como {@link #expect(String)}, com a versão lida antes pelo chamador. Vale a primeira registrada. */
    public void expect(String key, long version) {
        expected.putIfAbsent(key, version);
    }

    /** O lote tem condições, e por isso o commit pode falhar com conflito. */
    public boolean conditional() {
        return !expected.isEmpty();
    }

    /** Ação a executar só depois que o lote foi gravado (ex.: notificações de live query). */
    public void afterCommit(Runnable action) {
        afterCommit.add(action);
    }

    @Override
    public Optional<byte[]> get(String key) {
        if (deleted.contains(key)) {
            return Optional.empty();
        }
        byte[] v = staged.get(key);
        return v != null ? Optional.of(v.clone()) : base.get(key);
    }

    @Override
    public void put(String key, byte[] value) {
        staged.put(key, value.clone());
        deleted.remove(key);
    }

    @Override
    public boolean putIfAbsent(String key, byte[] value) {
        if (get(key).isPresent()) {
            return false;
        }
        put(key, value);
        return true;
    }

    @Override
    public boolean delete(String key) {
        if (deleted.contains(key)) {
            return false;
        }
        boolean wasStaged = staged.remove(key) != null;
        boolean inBase = base.get(key).isPresent();
        if (inBase) {
            deleted.add(key);
        }
        return wasStaged || inBase;
    }

    @Override
    public List<String> keysWithPrefix(String prefix) {
        TreeSet<String> keys = new TreeSet<>(base.keysWithPrefix(prefix));
        for (String key : staged.keySet()) {
            if (key.startsWith(prefix)) {
                keys.add(key);
            }
        }
        keys.removeAll(deleted);
        return List.copyOf(keys);
    }

    @Override
    public void scanPrefix(String prefix, Predicate<String> visitor) {
        if (staged.isEmpty() && deleted.isEmpty()) {
            base.scanPrefix(prefix, visitor);
            return;
        }
        merge(prefix, false, (key, value) -> visitor.test(key));
    }

    @Override
    public void scanEntries(String prefix, BiPredicate<String, byte[]> visitor) {
        if (staged.isEmpty() && deleted.isEmpty()) {
            base.scanEntries(prefix, visitor);
            return;
        }
        merge(prefix, true, visitor);
    }

    /** Intercala, em ordem de chave, o que o backend tem com o que este lote já escreveu ou apagou. */
    private void merge(String prefix, boolean withValues, BiPredicate<String, byte[]> visitor) {
        List<Map.Entry<String, byte[]>> pending = new ArrayList<>();
        for (var entry : staged.tailMap(prefix, true).entrySet()) {
            if (!entry.getKey().startsWith(prefix)) {
                break;
            }
            pending.add(entry);
        }
        int[] next = {0};
        boolean[] stopped = {false};
        BiPredicate<String, byte[]> fromBase = (key, value) -> {
            while (next[0] < pending.size() && pending.get(next[0]).getKey().compareTo(key) < 0) {
                var entry = pending.get(next[0]++);
                if (!visitor.test(entry.getKey(), entry.getValue())) {
                    stopped[0] = true;
                    return false;
                }
            }
            if (next[0] < pending.size() && pending.get(next[0]).getKey().equals(key)) {
                var entry = pending.get(next[0]++);
                if (!visitor.test(entry.getKey(), entry.getValue())) {
                    stopped[0] = true;
                    return false;
                }
                return true;
            }
            if (deleted.contains(key)) {
                return true;
            }
            if (!visitor.test(key, value)) {
                stopped[0] = true;
                return false;
            }
            return true;
        };
        if (withValues) {
            base.scanEntries(prefix, fromBase);
        } else {
            base.scanPrefix(prefix, key -> fromBase.test(key, null));
        }
        while (!stopped[0] && next[0] < pending.size()) {
            var entry = pending.get(next[0]++);
            if (!visitor.test(entry.getKey(), entry.getValue())) {
                return;
            }
        }
    }

    @Override
    public void flush() {
    }

    /** Número de escritas e remoções pendentes. */
    public int pending() {
        return staged.size() + deleted.size();
    }

    /** Grava tudo num lote atômico e roda as ações pós-commit. Sem escritas, não toca o backend. */
    public void commit() {
        // as condições valem mesmo sem escrita: um UPDATE que não achou a linha porque ela estava mudando de partição
        // precisa conflitar e ser refeito
        if (!staged.isEmpty() || !deleted.isEmpty() || !expected.isEmpty()) {
            base.commit(Map.copyOf(expected), staged, deleted, Set.of());
        }
        staged.clear();
        deleted.clear();
        expected.clear();
        List<Runnable> actions = List.copyOf(afterCommit);
        afterCommit.clear();
        for (Runnable action : actions) {
            action.run();
        }
    }

    /** Descarta o que não foi gravado. */
    public void discard() {
        staged.clear();
        deleted.clear();
        afterCommit.clear();
        expected.clear();
    }
}
