package com.axonbase.core;

import com.axonbase.core.engine.BackupFormat;
import com.axonbase.core.engine.BackupFormatException;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.core.storage.RocksDbBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Backup v2: manifesto, checksum e cadeia")
class BackupV2Test {

    @TempDir
    Path dir;

    private static Session session() {
        Session s = Session.create();
        s.namespace("app");
        s.database("main");
        return s;
    }

    static Map<String, String> snapshot(KvBackend b) {
        Map<String, String> m = new TreeMap<>();
        for (String k : b.keysWithPrefix("")) {
            m.put(k, java.util.Base64.getEncoder().encodeToString(b.get(k).orElseThrow()));
        }
        return m;
    }

    static byte[] export(Datastore ds, long since) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ds.backupTo(out, since);
        return out.toByteArray();
    }

    private Path file(byte[]... parts) throws IOException {
        Path f = Files.createTempFile(dir, "bk", ".axbak");
        try (java.io.OutputStream o = Files.newOutputStream(f)) {
            for (byte[] p : parts) {
                o.write(p);
            }
        }
        return f;
    }

    /** Aplica uma mutação e devolve o número de versão após ela (marca d'água do motor). */
    private static long mark(Datastore ds) {
        return ds.backupVersion();
    }

    @Test
    @DisplayName("completo + 3 incrementais com puts, updates e deletes restauram exatamente")
    void chainRestoresExactly() throws Exception {
        MemoryBackend src = new MemoryBackend();
        Datastore ds = new Datastore(src);
        ds.createDatabase("app", "main");
        Session s = session();
        ds.execute("DEFINE TABLE p SCHEMALESS", s, null);
        for (int i = 0; i < 50; i++) {
            ds.execute("CREATE p:" + i + " CONTENT {n: " + i + "}", s, null);
        }
        ds.metaPut("a", new byte[] {1});
        byte[] full = export(ds, 0);
        long v0 = mark(ds);

        ds.execute("CREATE p:100 CONTENT {n: 100}", s, null);
        ds.execute("UPDATE p:1 SET n = 999", s, null);
        ds.execute("DELETE p:2", s, null);
        byte[] inc1 = export(ds, v0);
        long v1 = mark(ds);

        ds.execute("DELETE p:3", s, null);
        ds.metaPut("a", new byte[] {2});
        ds.metaDelete("a");
        byte[] inc2 = export(ds, v1);
        long v2 = mark(ds);

        ds.execute("CREATE p:101 CONTENT {n: 101}", s, null);
        ds.execute("UPDATE p:100 SET n = 5", s, null);
        byte[] inc3 = export(ds, v2);

        MemoryBackend dst = new MemoryBackend();
        Datastore target = new Datastore(dst);
        Path chain = file(full, inc1, inc2, inc3);
        Datastore.RestoreResult r = target.restoreFile(chain);
        assertEquals(4, r.segments());
        assertEquals("chain", r.type());
        assertEquals(mark(ds), r.version());
        assertEquals(snapshot(src), snapshot(dst));
        // o completo sozinho também restaura (sem incrementais)
        assertEquals("full", new Datastore(new MemoryBackend()).restoreFile(file(full)).type());
    }

    @Test
    @DisplayName("incremental isolado e cadeia quebrada são recusados e a base fica intacta")
    void invalidChainsRejectedAndBaseIntact() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.metaPut("k0", new byte[] {0});
        byte[] full = export(ds, 0);
        long v0 = mark(ds);
        ds.metaPut("k1", new byte[] {1});
        byte[] inc1 = export(ds, v0);
        long v1 = mark(ds);
        ds.metaPut("k2", new byte[] {2});
        byte[] inc2 = export(ds, v1);

        MemoryBackend dst = new MemoryBackend();
        Datastore target = new Datastore(dst);
        target.createDatabase("x", "y");
        target.metaPut("intacto", new byte[] {9});
        Map<String, String> before = snapshot(dst);

        for (Path bad : new Path[] {file(inc1), file(inc2), file(full, inc2), file(full, inc1, inc1),
            file(full, inc2, inc1), file(inc1, full)}) {
            BackupFormatException e = assertThrows(BackupFormatException.class, () -> target.restoreFile(bad));
            assertEquals(BackupFormatException.CHAIN_INVALID, e.code());
            assertEquals(409, e.status());
            assertEquals(before, snapshot(dst), "a base mudou numa cadeia recusada");
        }
    }

    @Test
    @DisplayName("byte invertido e arquivo truncado são recusados sem tocar na base")
    void tamperedAndTruncatedRejected() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        for (int i = 0; i < 20; i++) {
            ds.metaPut("k" + i, ("valor-" + i).getBytes(StandardCharsets.UTF_8));
        }
        byte[] full = export(ds, 0);

        MemoryBackend dst = new MemoryBackend();
        Datastore target = new Datastore(dst);
        target.metaPut("intacto", new byte[] {9});
        Map<String, String> before = snapshot(dst);

        // adultera um caractere do Base64 de um valor, no meio do segmento
        byte[] tampered = full.clone();
        String fullText = new String(full, StandardCharsets.UTF_8);
        int pos = fullText.indexOf('\n', fullText.indexOf("ctrl\t")) + 6;
        tampered[pos] = (byte) (tampered[pos] == 'A' ? 'B' : 'A');
        BackupFormatException e = assertThrows(BackupFormatException.class, () -> target.restoreFile(file(tampered)));
        assertEquals(BackupFormatException.CHECKSUM, e.code());
        assertEquals(before, snapshot(dst));

        // sem a linha !end
        String text = new String(full, StandardCharsets.UTF_8);
        byte[] noEnd = text.substring(0, text.lastIndexOf("!end")).getBytes(StandardCharsets.UTF_8);
        e = assertThrows(BackupFormatException.class, () -> target.restoreFile(file(noEnd)));
        assertEquals(BackupFormatException.TRUNCATED, e.code());
        // cortado no meio de uma linha
        byte[] cut = java.util.Arrays.copyOf(full, full.length - 30);
        e = assertThrows(BackupFormatException.class, () -> target.restoreFile(file(cut)));
        assertEquals(BackupFormatException.TRUNCATED, e.code());
        assertEquals(before, snapshot(dst));
    }

    @Test
    @DisplayName("arquivo v1 legado continua restaurando")
    void legacyV1StillRestores() throws Exception {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        ds.metaPut("k", new byte[] {7});
        String v1 = ds.backupCluster();
        assertTrue(v1.startsWith(BackupFormat.HEADER_V1));
        Datastore target = Datastore.memory();
        Datastore.RestoreResult r = target.restoreFile(file(v1.getBytes(StandardCharsets.UTF_8)));
        assertEquals("legacy", r.type());
        assertEquals(7, target.metaGet("k").orElseThrow()[0]);
    }

    @Test
    @DisplayName("since adiante da marca d'água do motor da 409 BACKUP_SINCE_AHEAD")
    void sinceAheadRejected() {
        Datastore ds = Datastore.memory();
        ds.metaPut("k", new byte[] {1});
        BackupFormatException e = assertThrows(BackupFormatException.class,
            () -> export(ds, mark(ds) + 1000));
        assertEquals(BackupFormatException.SINCE_AHEAD, e.code());
        assertEquals(409, e.status());
    }

    @Test
    @DisplayName("ida e volta com RocksDB (snapshotScan) com deleções no incremental")
    void rocksRoundTripWithDeletes() throws Exception {
        try (RocksDbBackend a = new RocksDbBackend(dir.resolve("a").toString());
             RocksDbBackend b = new RocksDbBackend(dir.resolve("b").toString())) {
            Datastore ds = new Datastore(a);
            ds.createDatabase("app", "main");
            for (int i = 0; i < 200; i++) {
                ds.metaPut("k" + i, ("v" + i).getBytes(StandardCharsets.UTF_8));
            }
            byte[] full = export(ds, 0);
            long v0 = mark(ds);
            ds.metaPut("k5", new byte[] {5, 5});
            ds.metaDelete("k6");
            ds.metaDelete("k7");
            ds.metaPut("novo", new byte[] {1});
            byte[] inc = export(ds, v0);
            String incText = new String(inc, StandardCharsets.UTF_8);
            assertTrue(incText.contains("!del\t"));

            Datastore target = new Datastore(b);
            target.restoreFile(file(full, inc));
            assertEquals(snapshot(a), snapshot(b));
        }
    }
}
