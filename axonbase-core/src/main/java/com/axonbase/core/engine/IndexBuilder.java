package com.axonbase.core.engine;

import com.axonbase.common.AxonError;
import com.axonbase.core.Session;
import com.axonbase.core.catalog.Catalog;
import com.axonbase.core.catalog.Database;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;

/**
 * Constrói um índice sobre dados que já existem sem parar as escritas (E1.2).
 *
 * <p>O índice entra no catálogo em estado "em construção": a escrita normal já o mantém, a consulta ainda
 * não o usa. A varredura anda em blocos de {@link #CHUNK} linhas, e cada bloco segura por pouco tempo o
 * lado de escrita do {@code indexLock} do banco (as escritas seguram o lado de leitura durante o
 * statement), então nenhum bloco enxerga um statement pela metade. Ao fim, o marcador de construção é
 * apagado no mesmo lote que grava as chaves de "pronto"; se a construção falha ou é abortada, o índice sai
 * do catálogo e suas entradas são apagadas. Um marcador que sobra de uma queda faz o reinício refazer a
 * construção, então um índice parcial nunca vira pronto.</p>
 */
public final class IndexBuilder {

    /** Linhas por bloco: segura o lock de escrita por alguns milissegundos. */
    static final int CHUNK = 500;

    /** Espera antes de refazer uma construção invalidada por escritas no formato antigo. */
    private static final long RETRY_PAUSE_MS = 500;

    /** Simula a queda do processo no meio da construção: a thread morre sem limpar nada. Só para testes. */
    public static final class Crash extends RuntimeException {
        public Crash() {
            super("queda simulada");
        }
    }

    private static final ConcurrentMap<BuildKey, Future<?>> RUNNING = new ConcurrentHashMap<>();
    private static final ExecutorService POOL = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "axon-index-build");
        t.setDaemon(true);
        return t;
    });
    private static volatile Runnable chunkHook;

    private IndexBuilder() {
    }

    /** Gancho chamado antes de cada bloco, para os testes pararem ou derrubarem a construção. */
    public static void chunkHook(Runnable hook) {
        chunkHook = hook;
    }

    /** Espera as construções em segundo plano terminarem. */
    public static boolean awaitAll(long timeoutMs) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (!RUNNING.isEmpty()) {
            if (System.nanoTime() > deadline) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    /** Identidade da construção: inclui o Datastore, pois dois no mesmo processo podem ter o mesmo ns/db/índice. */
    private record BuildKey(Datastore ds, String path) {
        @Override
        public boolean equals(Object o) {
            return o instanceof BuildKey other && other.ds == ds && other.path.equals(path);
        }

        @Override
        public int hashCode() {
            return 31 * System.identityHashCode(ds) + path.hashCode();
        }
    }

    private static BuildKey key(Datastore ds, String ns, String db, String table, String name) {
        return new BuildKey(ds, ns + "|" + db + "|" + table + "|" + name);
    }

    /** Inicia a construção numa thread própria, a menos que já haja uma para o mesmo índice. */
    static void startAsync(Datastore ds, String ns, String dbName, String table, String name, boolean purgeFirst) {
        BuildKey k = key(ds, ns, dbName, table, name);
        java.util.concurrent.CompletableFuture<Void> gate = new java.util.concurrent.CompletableFuture<>();
        if (RUNNING.putIfAbsent(k, gate) != null) {
            return;
        }
        POOL.execute(() -> {
            try {
                Executor ex = new Executor(ds).forBuild(new Session(ns, dbName));
                build(ds, ex, ns, dbName, table, name, purgeFirst);
            } catch (Crash crash) {
                // a thread cai sem limpar, como o processo cairia
            } catch (RuntimeException e) {
                // build() já abortou e registrou a falha
            } finally {
                RUNNING.remove(k);
                gate.complete(null);
            }
        });
    }

    /** Como {@link #startAsync}, mas se a thread anterior ainda está saindo, começa outra quando ela terminar. */
    static void restartAsync(Datastore ds, String ns, String dbName, String table, String name) {
        Future<?> leaving = RUNNING.get(key(ds, ns, dbName, table, name));
        if (leaving instanceof java.util.concurrent.CompletableFuture<?> running) {
            running.thenRun(() -> startAsync(ds, ns, dbName, table, name, true));
        } else {
            startAsync(ds, ns, dbName, table, name, true);
        }
    }

    /**
     * Roda a construção na thread atual. Em falha, aborta o índice (limpa tudo) e relança o erro.
     *
     * @param purgeFirst apaga entradas parciais antes de começar (retomada depois de queda)
     */
    static void build(Datastore ds, Executor ex, String ns, String dbName, String table, String name,
                      boolean purgeFirst) {
        try {
            if (purgeFirst) {
                purge(ds, ex, ns, dbName, table, name);
            }
            while (true) {
                Catalog.IndexDef building = lookup(ds.findDatabase(ns, dbName), table, name);
                if (scan(ds, ex, ns, dbName, table, name)
                    && finish(ds, ex, ns, dbName, table, name, building)) {
                    return;
                }
                // o catálogo foi recarregado no meio, ou chegaram escritas no formato antigo: refaz do zero
                pause();
                Catalog.IndexDef current = lookup(ds.findDatabase(ns, dbName), table, name);
                if (current == null || current.ready()) {
                    return;
                }
                purge(ds, ex, ns, dbName, table, name);
            }
        } catch (Crash crash) {
            throw crash;
        } catch (RuntimeException e) {
            abort(ds, ex, ns, dbName, table, name, e);
            throw e;
        }
    }

    private static void purge(Datastore ds, Executor ex, String ns, String dbName, String table, String name) {
        Database db = ds.findDatabase(ns, dbName);
        Catalog.IndexDef index = lookup(db, table, name);
        if (index != null && !index.ready()) {
            // as chaves do formato antigo podem ser milhões e ninguém daqui as escreve: saem sem parar as escritas
            ex.dropLegacyGeoEntries(db, table, index);
            Lock w = db.indexLock().writeLock();
            w.lock();
            try {
                ex.purgeForRebuild(db, table, index);
            } finally {
                w.unlock();
            }
        }
    }

    private static void pause() {
        try {
            Thread.sleep(RETRY_PAUSE_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw AxonError.internal(e.toString());
        }
    }

    private static Catalog.IndexDef lookup(Database db, String table, String name) {
        if (db == null) {
            return null;
        }
        Catalog.TableDef def = db.catalog().table(table);
        return def == null ? null : def.indexes().get(name);
    }

    /** @return {@code false} quando a varredura parou antes do fim da tabela */
    private static boolean scan(Datastore ds, Executor ex, String ns, String dbName, String table, String name) {
        Database start = ds.findDatabase(ns, dbName);
        if (start == null) {
            return false;
        }
        String prefix = ns + "\u0000" + dbName + "\u0000" + table + "\u0000";
        List<String> chunk = new ArrayList<>(CHUNK);
        boolean[] stop = {false};
        start.records().scanPrefix(prefix, k -> {
            String part = k.substring(prefix.length());
            // chaves de índice (!uidx...) moram sob o mesmo prefixo da tabela e não são registros
            if (part.isEmpty() || part.charAt(0) == '!') {
                return true;
            }
            chunk.add(part);
            if (chunk.size() >= CHUNK) {
                stop[0] = !processChunk(ds, ex, ns, dbName, table, name, chunk);
                chunk.clear();
            }
            return !stop[0];
        });
        if (!stop[0] && !chunk.isEmpty()) {
            stop[0] = !processChunk(ds, ex, ns, dbName, table, name, chunk);
        }
        return !stop[0];
    }

    /** @return {@code false} quando a construção deve parar (índice removido ou já pronto) */
    private static boolean processChunk(Datastore ds, Executor ex, String ns, String dbName, String table,
                                        String name, List<String> keys) {
        Runnable hook = chunkHook;
        if (hook != null) {
            hook.run();
        }
        // o banco é resolvido a cada bloco: um replay do plano de controle troca os objetos em memória
        Database db = ds.findDatabase(ns, dbName);
        Catalog.IndexDef index = lookup(db, table, name);
        if (index == null || index.ready()) {
            return false;
        }
        Lock w = db.indexLock().writeLock();
        w.lock();
        try {
            index = lookup(db, table, name);
            if (index == null || index.ready()) {
                return false;
            }
            ex.buildChunk(db, table, index, keys);
            index.addBuilt(keys.size());
            return true;
        } finally {
            w.unlock();
        }
    }

    /**
     * @param scanned a definição que a varredura percorreu
     * @return {@code false} quando a construção precisa ser refeita
     */
    private static boolean finish(Datastore ds, Executor ex, String ns, String dbName, String table, String name,
                                  Catalog.IndexDef scanned) {
        Database db = ds.findDatabase(ns, dbName);
        if (db == null) {
            return false;
        }
        Lock w = db.indexLock().writeLock();
        w.lock();
        try {
            Catalog.IndexDef index = lookup(db, table, name);
            if (index != null && !index.ready()) {
                if (index != scanned) {
                    return false; // outra definição entrou no lugar: parte das linhas pode ter ficado de fora
                }
                if (!ex.finishIndex(db, table, index)) {
                    return false;
                }
                index.markReady();
                // um lote replicado pode ter chegado entre a conferência e a marca; ele só olha índices prontos,
                // então um dos dois lados sempre o vê
                if (ex.hasLegacyGeoEntries(db, table, index)) {
                    index.markBuilding();
                    return false;
                }
            }
            return true;
        } finally {
            w.unlock();
        }
    }

    private static void abort(Datastore ds, Executor ex, String ns, String dbName, String table, String name,
                              RuntimeException cause) {
        Database db = ds.findDatabase(ns, dbName);
        if (db == null) {
            return;
        }
        Lock w = db.indexLock().writeLock();
        w.lock();
        try {
            Catalog.IndexDef index = lookup(db, table, name);
            if (index != null && !index.ready()) {
                String message = cause instanceof AxonError ? cause.getMessage()
                    : cause.getClass().getSimpleName() + ": " + cause.getMessage();
                ex.abortIndex(db, table, index, message);
            }
        } finally {
            w.unlock();
        }
    }
}
