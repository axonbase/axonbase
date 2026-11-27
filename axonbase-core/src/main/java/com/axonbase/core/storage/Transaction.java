package com.axonbase.core.storage;

import com.axonbase.common.Messages;

import java.util.List;
import java.util.Optional;
import java.util.TreeMap;
import java.util.HashMap;

/**
 * Transacción: unha vista {@link KvBackend} que bufferiza os writes e deixa ver
 * os cambios non confirmados. As lecturas len o staged e, se non, o backend
 * subxacente (read-through), o que dá unha snapshot consistente dentro da
 * transacción.
 * <p>
 * {@code commit()} aplica o staged ó backend base; {@code cancel()} descarta.
 * <p>
 * Admite savepoints (ver {@link #savepoint(String)}) e rexistra os rangos varridos
 * con {@link #keysWithPrefix(String)} para detectar loves fantasma no commit.
 */
public final class Transaction implements KvBackend {

    private final KvBackend base;
    private final TreeMap<String, byte[]> staged = new TreeMap<>();
    private final java.util.Set<String> deleted = new java.util.HashSet<>();
    /** Valor visto na primeira lectura, para lecturas repetibles por chave. */
    private final java.util.Map<String, Optional<byte[]>> snapshot = new HashMap<>();
    /** Versión esperada no commit otimista. */
    private final java.util.Map<String, Long> readVersions = new HashMap<>();
    /** Prefixos varridos con {@link #keysWithPrefix}, para validar le fantasma. */
    private final java.util.Set<String> readRanges = new java.util.HashSet<>();
    /** Pila de savepoints: estado de writes/deletes en cada checkpoint. */
    private final java.util.ArrayDeque<SavepointSnapshot> savepoints = new java.util.ArrayDeque<>();
    private boolean open = true;
    private final long openedAtMs = System.currentTimeMillis();
    /** Faixas que o commit vai apagar: para esta transação, o que está nelas na base já não existe. */
    private final java.util.List<RangeDelete.Range> dropped = new java.util.ArrayList<>();
    /** Condições conferidas (e travas tomadas) no commit, uma por identificador. */
    private final java.util.Map<String, CommitGuard> guards = new java.util.LinkedHashMap<>();

    public Transaction(KvBackend base) {
        this.base = base;
    }

    public boolean isOpen() {
        return open;
    }
    /** Quando a transação foi aberta (epoch em milissegundos). */
    public long openedAtMs() { return openedAtMs; }
    /** Quantidade de escritas e de remoções pendentes (leitura aproximada, sem copiar). */
    public int stagedWriteCount() { return staged.size(); }
    public int stagedDeleteCount() { return deleted.size(); }
    public java.util.Map<String, byte[]> stagedWrites() { return java.util.Map.copyOf(staged); }
    public java.util.Set<String> stagedDeletes() { return java.util.Set.copyOf(deleted); }

    @Override
    public Optional<byte[]> get(String key) {
        ensureOpen();
        if (deleted.contains(key)) {
            return Optional.empty();
        }
        byte[] v = staged.get(key);
        if (v != null) {
            return Optional.of(v.clone());
        }
        if (isDropped(key)) {
            return Optional.empty();
        }
        Optional<byte[]> cached = snapshot.get(key);
        if (cached != null) {
            return cached.map(byte[]::clone);
        }
        Optional<byte[]> read;
        if (base instanceof VersionedKvBackend versioned) {
            Optional<VersionedKvBackend.VersionedValue> value = versioned.getVersioned(key);
            readVersions.put(key, value.map(VersionedKvBackend.VersionedValue::version)
                .orElseGet(() -> versioned.versionOf(key)));
            read = value.map(VersionedKvBackend.VersionedValue::value);
        } else {
            read = base.get(key);
        }
        Optional<byte[]> frozen = read.map(byte[]::clone);
        snapshot.put(key, frozen);
        return frozen.map(byte[]::clone);
    }

    @Override
    public void put(String key, byte[] value) {
        ensureOpen();
        // Toda escritura ten unha precondición, inclusive CREATE sen lectura previa.
        if (!staged.containsKey(key) && !snapshot.containsKey(key)) {
            get(key);
        }
        stageMarker(key, value);
        staged.put(key, value.clone());
        deleted.remove(key);
    }

    /** A chave está numa faixa que esta transação vai apagar (e não foi gravada de novo depois). */
    public boolean isDropped(String key) {
        for (RangeDelete.Range range : dropped) {
            if (range.contains(key)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Uma marca de remoção por faixa: o que a transação já tinha gravado ali deixa de existir (no commit a faixa é
     * apagada antes das gravações, que de outro modo a sobreviveriam) e as leituras seguintes não veem mais a base.
     */
    private void stageMarker(String key, byte[] value) {
        if (!RangeDelete.isMarker(key)) {
            return;
        }
        RangeDelete.Range range = RangeDelete.parse(key, value);
        staged.subMap(range.from(), true, range.to(), false).clear();
        dropped.add(range);
    }

    /**
     * Lê como {@link #get}, mas sem registrar a leitura: outra transação que mude a chave não conflita com esta.
     * Serve para metadados cuja validade é conferida por uma {@link CommitGuard}.
     */
    public Optional<byte[]> peek(String key) {
        ensureOpen();
        if (deleted.contains(key)) {
            return Optional.empty();
        }
        byte[] v = staged.get(key);
        if (v != null) {
            return Optional.of(v.clone());
        }
        return isDropped(key) ? Optional.empty() : base.get(key);
    }

    /** Lista as chaves do prefixo como {@link #keysWithPrefix}, sem registrar a faixa nem as leituras. */
    public List<String> peekKeys(String prefix) {
        ensureOpen();
        java.util.TreeSet<String> keys = new java.util.TreeSet<>(base.keysWithPrefix(prefix));
        keys.removeIf(this::isDropped);
        for (String k : staged.keySet()) {
            if (k.startsWith(prefix)) {
                keys.add(k);
            }
        }
        keys.removeAll(deleted);
        return List.copyOf(keys);
    }

    /**
     * Esquece as faixas varridas: o commit deixa de conferir leitura fantasma (e de correr sozinho no armazenamento).
     * As chaves lidas continuam conferidas. Para o trabalho de segundo plano, que não pode parar as escritas.
     */
    public void forgetScans() {
        ensureOpen();
        readRanges.clear();
    }

    /** Grava sem exigir que a chave esteja igual no commit: a última gravação vence. */
    public void putBlind(String key, byte[] value) {
        ensureOpen();
        stageMarker(key, value);
        staged.put(key, value.clone());
        deleted.remove(key);
    }

    /** Condição do commit: pega o que precisar, confere e devolve o que soltar depois de gravar. */
    public interface CommitGuard {
        /** Condições exclusivas entram primeiro: quem já tem a escrita pode pegar a leitura, o contrário trava. */
        boolean exclusive();

        /** @throws VersionConflictException quando a condição não vale mais */
        Held enter();
    }

    /** O que soltar depois do commit. */
    public interface Held extends AutoCloseable {
        @Override
        void close();
    }

    /** Registra uma condição; um identificador repetido vale uma vez. */
    public void guard(String id, CommitGuard guard) {
        ensureOpen();
        guards.putIfAbsent(id, guard);
    }

    /** Há condições a conferir no commit. */
    public boolean hasGuards() {
        return !guards.isEmpty();
    }

    /** Entra em todas as condições. Se uma falhar, solta as anteriores e relança. */
    public Held enterGuards() {
        java.util.Deque<Held> held = new java.util.ArrayDeque<>();
        try {
            for (boolean exclusive : new boolean[] {true, false}) {
                for (CommitGuard guard : guards.values()) {
                    if (guard.exclusive() == exclusive) {
                        held.push(guard.enter());
                    }
                }
            }
        } catch (RuntimeException e) {
            held.forEach(Held::close);
            throw e;
        }
        return () -> held.forEach(Held::close);
    }

    @Override
    public boolean putIfAbsent(String key, byte[] value) {
        ensureOpen();
        if (staged.containsKey(key) || deleted.contains(key)) {
            return false;
        }
        if (get(key).isPresent()) {
            return false;
        }
        put(key, value);
        return true;
    }

    @Override
    public boolean delete(String key) {
        ensureOpen();
        boolean existed = staged.containsKey(key) || (!deleted.contains(key) && get(key).isPresent());
        if (!existed) {
            return false;
        }
        staged.remove(key);
        deleted.add(key);
        return true;
    }

    @Override
    public List<String> keysWithPrefix(String prefix) {
        ensureOpen();
        // Rexistra o rango varrido para que o commit detecte chaves novas que
        // aparezan neste prefixo despois (protección contra loves fantasma).
        readRanges.add(prefix);
        java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        for (String k : base.keysWithPrefix(prefix)) {
            if (isDropped(k)) {
                continue;
            }
            // Captura o valor/versão de cada chave do scan para validá-la no commit.
            get(k);
            keys.add(k);
        }
        for (String k : staged.keySet()) {
            if (k.startsWith(prefix)) {
                keys.add(k);
            }
        }
        keys.removeAll(deleted);
        return List.copyOf(keys);
    }

    @Override
    public void flush() {
    }

    /** Aplica o staged ó backend base e pecha a transacción. */
    public void commit() {
        ensureOpen();
        if (base instanceof VersionedKvBackend versioned) {
            versioned.commit(java.util.Map.copyOf(readVersions), java.util.Map.copyOf(staged),
                java.util.Set.copyOf(deleted), java.util.Set.copyOf(readRanges));
            finish();
            return;
        }
        for (String k : deleted) {
            base.delete(k);
        }
        for (var e : staged.entrySet()) {
            base.put(e.getKey(), e.getValue());
        }
        finish();
    }

    /**
     * Verifica as precondicións otimistas (versións por chave) sen aplicar nada.
     *
     * <p>Num cluster, a validación ten de acontecer antes de replicar: despois do
     * consenso aplicar o batch, as versións que esta transacción leu xa cambiaron por
     * causa dela mesma, e revalidar acusaría un conflito inexistente. Por iso neste
     * camiño se comproban as versións por chave, pero <b>non se validan os rangos</b>
     * varridos ({@code readRanges}): os scans de live queries e índices non deben
     * regresionar no consenso.</p>
     */
    public void validate() {
        ensureOpen();
        if (base instanceof VersionedKvBackend versioned) {
            versioned.commit(java.util.Map.copyOf(readVersions), java.util.Map.of(),
                java.util.Set.of(), java.util.Set.of());
        }
    }

    /** Chaves lidas, escritas ou apagadas: as que decidem se outra transação conflita com esta. */
    public java.util.Set<String> touchedKeys() {
        java.util.Set<String> keys = new java.util.HashSet<>(readVersions.keySet());
        keys.addAll(staged.keySet());
        keys.addAll(deleted);
        return keys;
    }

    /** Fecha a transação cujas escritas já foram aplicadas ao backend pelo consenso. */
    public void finishApplied() {
        ensureOpen();
        finish();
    }

    /**
     * Aplica o staged sen revalidar as precondicións e pecha a transacción.
     *
     * <p>Usada cando o consenso xa aceitou este batch: a orde foi decidida no log e
     * as precondicións foron verificadas en {@link #validate()} antes diso. Non valida
     * os rangos de lectura: o batch replicado xa existe no storage e volver a comprobar
     * os prefixos varridos acusaría falsos positivos do propio commit (non se checan).</p>
     */
    public void commitValidated() {
        ensureOpen();
        if (base instanceof VersionedKvBackend versioned) {
            versioned.commit(java.util.Map.of(), java.util.Map.copyOf(staged),
                java.util.Set.copyOf(deleted), java.util.Set.of());
            finish();
            return;
        }
        commit();
    }

    // ------------------------------------------------------------------
    // savepoints
    // ------------------------------------------------------------------

    /**
     * Estado de writes e deletes nun checkpoint. Os reads (snapshot e versións) viven
     * fóra do savepoint e non se botan ao retroceder.
     */
    private record SavepointSnapshot(String name, java.util.Map<String, byte[]> staged,
                                     java.util.Set<String> deleted, java.util.List<RangeDelete.Range> dropped) {
    }

    /** Crea un checkpoint co estado actual dos writes e deletes, baixo o nome dado. */
    public void savepoint(String name) {
        ensureOpen();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(Messages.get("txn_savepoint_name_required"));
        }
        savepoints.push(new SavepointSnapshot(name,
            new java.util.TreeMap<>(staged), new java.util.HashSet<>(deleted), java.util.List.copyOf(dropped)));
    }

    /**
     * Elimina o marcador do savepoint dado e os que estean por riba, mantendo os
     * writes e deletes feitos despois del.
     */
    public void release(String name) {
        ensureOpen();
        java.util.Deque<SavepointSnapshot> above = new java.util.ArrayDeque<>();
        boolean found = false;
        while (!savepoints.isEmpty()) {
            SavepointSnapshot sp = savepoints.pop();
            if (sp.name().equals(name)) {
                found = true;
                break;
            }
            above.push(sp);
        }
        if (!found) {
            while (!above.isEmpty()) {
                savepoints.push(above.pop());
            }
            throw new IllegalArgumentException(Messages.get("txn_savepoint_not_found", name));
        }
    }

    /**
     * Retrocede ao estado dos writes/deletes existente cando se creou o savepoint
     * nomeado, descartando o savepoint e todos os creados despois del. As lecturas
     * xa feitas (snapshot e versións) non se ven afectadas.
     */
    public void rollbackTo(String name) {
        ensureOpen();
        java.util.Deque<SavepointSnapshot> above = new java.util.ArrayDeque<>();
        SavepointSnapshot target = null;
        boolean found = false;
        while (!savepoints.isEmpty()) {
            SavepointSnapshot sp = savepoints.pop();
            above.push(sp);
            if (sp.name().equals(name)) {
                target = sp;
                found = true;
                break;
            }
        }
        if (!found) {
            while (!above.isEmpty()) {
                savepoints.push(above.pop());
            }
            throw new IllegalArgumentException(Messages.get("txn_savepoint_not_found", name));
        }
        staged.clear();
        staged.putAll(target.staged());
        deleted.clear();
        deleted.addAll(target.deleted());
        dropped.clear();
        dropped.addAll(target.dropped());
    }

    private void finish() {
        open = false;
        staged.clear();
        deleted.clear();
        snapshot.clear();
        readVersions.clear();
        readRanges.clear();
        savepoints.clear();
        guards.clear();
        dropped.clear();
    }

    /** Descarta o estado en tocar o base. */
    public void cancel() {
        finish();
    }

    private void ensureOpen() {
        if (!open) {
            throw new IllegalStateException(Messages.get("txn_closed"));
        }
    }
}
