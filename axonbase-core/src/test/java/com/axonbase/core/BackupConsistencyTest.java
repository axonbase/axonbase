package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Portão da E1.0: backups completos tirados enquanto statements atômicos (registro mais entradas de índice
 * UNIQUE e COLUMNAR) rodam em paralelo precisam restaurar coerentes, sem exceção.
 */
@DisplayName("Backup sob escrita concorrente restaura registros e índices coerentes (RocksDB)")
class BackupConsistencyTest {

    private static final String REC = "test\0dev\0p\0n";
    private static final String UNIQ = "test\0dev\0p\0!uidx\0codigo\0";
    private static final String COL = "CI|test|dev|p|p_st|status|";
    private static final Pattern TAIL = Pattern.compile("n(\\d+)$");
    private static final Pattern UNIQ_TAIL = Pattern.compile("\0n(\\d+)$");
    private static final int PREPOP = 6000;
    private static final int EXPORTS = 5;

    @TempDir
    Path dir;

    private static Session session() {
        Session s = Session.create();
        s.namespace("test");
        s.database("dev");
        return s;
    }

    /** Violações do invariante: registro sem índice, índice sem registro, ou entrada duplicada. */
    static List<String> violations(Collection<String> keys) {
        Map<Long, Integer> records = new HashMap<>();
        Map<Long, Integer> unique = new HashMap<>();
        Map<Long, Integer> columnar = new HashMap<>();
        for (String key : keys) {
            if (key.startsWith(UNIQ)) {
                Matcher m = UNIQ_TAIL.matcher(key);
                if (m.find()) {
                    unique.merge(Long.parseLong(m.group(1)), 1, Integer::sum);
                }
            } else if (key.startsWith(REC)) {
                records.merge(Long.parseLong(key.substring(REC.length())), 1, Integer::sum);
            } else if (key.startsWith(COL)) {
                Matcher m = TAIL.matcher(key);
                if (m.find()) {
                    columnar.merge(Long.parseLong(m.group(1)), 1, Integer::sum);
                }
            }
        }
        List<String> out = new ArrayList<>();
        for (Long id : records.keySet()) {
            if (unique.getOrDefault(id, 0) != 1) {
                out.add("registro " + id + " com " + unique.getOrDefault(id, 0) + " entradas UNIQUE");
            }
            if (columnar.getOrDefault(id, 0) != 1) {
                out.add("registro " + id + " com " + columnar.getOrDefault(id, 0) + " entradas COLUMNAR");
            }
        }
        for (Long id : unique.keySet()) {
            if (!records.containsKey(id)) {
                out.add("entrada UNIQUE órfã do registro " + id);
            }
        }
        for (Long id : columnar.keySet()) {
            if (!records.containsKey(id)) {
                out.add("entrada COLUMNAR órfã do registro " + id);
            }
        }
        return out;
    }

    /** O algoritmo antigo: lista as chaves e faz um get por chave no estado atual (não é ponto no tempo). */
    private static List<String> legacyKeys(RocksDbBackend backend) {
        List<String> keys = new ArrayList<>();
        backend.scanPrefix("", key -> {
            if (backend.get(key).isPresent()) {
                keys.add(key);
            }
            return true;
        });
        return keys;
    }

    @Test
    @DisplayName("5 backups completos sob escrita atômica restauram sem nenhuma violação")
    void backupsUnderConcurrentWritesRestoreCoherent() throws Exception {
        try (RocksDbBackend backend = new RocksDbBackend(dir.resolve("db").toString())) {
            Datastore ds = new Datastore(backend);
            ds.createDatabase("test", "dev");
            Session s = session();
            ds.execute("DEFINE TABLE p SCHEMALESS; CREATE INDEX p_cod ON TABLE p COLUMNS codigo UNIQUE; "
                + "CREATE INDEX p_st ON TABLE p COLUMNS status COLUMNAR", s, null);
            for (int base = 0; base < PREPOP; base += 500) {
                StringBuilder sb = new StringBuilder("INSERT INTO p [");
                for (int i = base; i < base + 500; i++) {
                    sb.append(i == base ? "" : ", ").append("{id: ").append(i).append(", codigo: 'c").append(i)
                        .append("', status: 'novo'}");
                }
                ds.execute(sb.append(']').toString(), s, null);
            }
            assertEquals(List.of(), violations(legacyKeys(backend)), "estado parado já incoerente");

            AtomicBoolean stop = new AtomicBoolean();
            AtomicLong ids = new AtomicLong(PREPOP);
            AtomicLong statements = new AtomicLong();
            List<Throwable> errors = new ArrayList<>();
            List<Thread> writers = new ArrayList<>();
            for (int t = 0; t < 4; t++) {
                Thread w = new Thread(() -> {
                    Session ws = session();
                    long mine = 0;
                    try {
                        while (!stop.get()) {
                            long id = ids.getAndIncrement();
                            ds.execute("CREATE p:" + id + " CONTENT {codigo: 'c" + id + "', status: 'novo'}", ws, null);
                            ds.execute("UPDATE p:" + id + " SET status = 'pronto'", ws, null);
                            if (++mine % 3 == 0) {
                                ds.execute("DELETE p:" + (id - 200), ws, null);
                            }
                            statements.addAndGet(2);
                        }
                    } catch (Throwable e) {
                        synchronized (errors) {
                            errors.add(e);
                        }
                    }
                }, "writer-" + t);
                w.start();
                writers.add(w);
            }

            List<byte[]> exports = new ArrayList<>();
            List<List<String>> legacyDumps = new ArrayList<>();
            for (int i = 0; i < EXPORTS; i++) {
                Thread.sleep(80L + 60L * i);
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                // mesmo instante lógico: o dump antigo (por chave) e o novo (snapshot) correm juntos
                Thread legacy = new Thread(() -> legacyDumps.add(legacyKeys(backend)));
                legacy.start();
                ds.backupTo(out, 0);
                legacy.join();
                exports.add(out.toByteArray());
            }
            stop.set(true);
            for (Thread w : writers) {
                w.join();
            }
            assertTrue(errors.isEmpty(), "writers falharam: " + errors);
            assertTrue(statements.get() > 100, "poucos statements concorrentes: " + statements.get());

            int legacyBad = 0;
            for (List<String> dump : legacyDumps) {
                if (!violations(dump).isEmpty()) {
                    legacyBad++;
                }
            }
            System.out.println("CONSISTENCIA statements=" + statements.get() + " dumpsAntigosIncoerentes="
                + legacyBad + "/" + legacyDumps.size());

            for (int i = 0; i < exports.size(); i++) {
                MemoryBackend restored = new MemoryBackend();
                Datastore target = new Datastore(restored);
                Path f = Files.createTempFile(dir, "exp", ".axbak");
                Files.write(f, exports.get(i));
                target.restoreFile(f);
                List<String> bad = violations(restored.keysWithPrefix(""));
                assertTrue(bad.isEmpty(), "restore " + i + " incoerente: " + bad.subList(0, Math.min(5, bad.size())));
                assertTrue(restored.keysWithPrefix(REC).size() > PREPOP / 2, "restore " + i + " vazio");
            }
        }
    }
}
