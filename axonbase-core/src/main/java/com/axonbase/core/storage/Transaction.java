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

    public Transaction(KvBackend base) {
        this.base = base;
    }

    public boolean isOpen() {
        return open;
    }
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
        staged.put(key, value.clone());
        deleted.remove(key);
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
                                     java.util.Set<String> deleted) {
    }

    /** Crea un checkpoint co estado actual dos writes e deletes, baixo o nome dado. */
    public void savepoint(String name) {
        ensureOpen();
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(Messages.get("txn_savepoint_name_required"));
        }
        savepoints.push(new SavepointSnapshot(name,
            new java.util.TreeMap<>(staged), new java.util.HashSet<>(deleted)));
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
    }

    private void finish() {
        open = false;
        staged.clear();
        deleted.clear();
        snapshot.clear();
        readVersions.clear();
        readRanges.clear();
        savepoints.clear();
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
