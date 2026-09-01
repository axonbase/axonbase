package com.axonbase.core.backup;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

public class S3SyncManagerTest {

    public static class InMemoryBackupStore implements RemoteBackupStore {
        final ConcurrentHashMap<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public void upload(String bucket, String key, InputStream data) {
            try { objects.put(key, data.readAllBytes()); }
            catch (Exception e) { throw new RuntimeException(e); }
        }

        @Override
        public byte[] download(String bucket, String key) {
            return objects.getOrDefault(key, new byte[0]);
        }

        @Override
        public List<String> listObjects(String bucket, String prefix) {
            return objects.keySet().stream()
                .filter(k -> k.startsWith(prefix))
                .sorted()
                .toList();
        }

        @Override
        public void deleteObject(String bucket, String key) {
            objects.remove(key);
        }
    }

    @SuppressWarnings("unchecked")
    private static MemoryBackend backendOf(Datastore ds) {
        try {
            Field f = Datastore.class.getDeclaredField("backend");
            f.setAccessible(true);
            return (MemoryBackend) f.get(ds);
        } catch (Exception e) {
            throw new AssertionError("nao conseguiu extrair backend", e);
        }
    }

    @Test
    void incrementalComLastIndexZeroRetornaFull() {
        var backend = new MemoryBackend();
        Datastore ds = new Datastore(backend);
        ds.createDatabase("ns", "db");
        backend.put("k1", "v1".getBytes());
        backend.put("k2", "v2".getBytes());
        String full = ds.backupIncremental(0);
        assertTrue(full.startsWith(S3Encryption.PLAIN_HEADER));
        assertTrue(full.contains("k1\t" + Base64.getEncoder().encodeToString("v1".getBytes())));
        assertTrue(full.contains("k2\t" + Base64.getEncoder().encodeToString("v2".getBytes())));
    }

    @Test
    void incrementalSoIncluiChavesComVersaoMaior() {
        var backend = new MemoryBackend();
        Datastore ds = new Datastore(backend);
        ds.createDatabase("ns", "db");
        backend.put("a", "1".getBytes());
        backend.put("b", "2".getBytes());
        long vb = backend.versionOf("b");
        String inc = ds.backupIncremental(vb);
        assertFalse(inc.contains("\na\t"));
        assertFalse(inc.contains("\nb\t"));
        backend.put("c", "3".getBytes());
        String inc2 = ds.backupIncremental(vb);
        assertTrue(inc2.contains("\nc\t"));
    }

    @Test
    void encryptionRoundTrip() {
        String dump = "--axonbase-backup-v1--\nctrl\tdGVzdA==\nkey\tdmFsdWU=\n";
        String pw = "senha-forte";
        String enc = S3Encryption.encrypt(dump, pw);
        assertTrue(enc.startsWith(S3Encryption.ENCRYPTED_HEADER));
        assertEquals(dump, S3Encryption.decrypt(enc, pw));
    }

    @Test
    void encryptionComSenhaErradaLancaErro() {
        String enc = S3Encryption.encrypt("secreto", "certa");
        assertThrows(RuntimeException.class, () -> S3Encryption.decrypt(enc, "errada"));
    }

    @Test
    void decryptionDeTextoPlanoPassaDireto() {
        assertEquals("plain\n", S3Encryption.decrypt("plain\n", "senha"));
    }

    @Test
    void rotationMantemApenasOsMaisNovos() {
        var store = new InMemoryBackupStore();
        var stamp = new AtomicInteger(1);
        Datastore ds = Datastore.memory();
        ds.createDatabase("test", "main");

        store.objects.put("cluster-10000000-010100.axbak", "old1".getBytes());
        store.objects.put("cluster-10000000-010101.axbak", "old2".getBytes());
        store.objects.put("cluster-10000000-010102.axbak", "old3".getBytes());

        var mgr = new S3SyncManager(ds, store, "bucket", "", 60_000L, 2, "",
            () -> 0L, () -> String.format("20000000-0202%02d", stamp.getAndIncrement()));
        mgr.backupNow();

        List<String> remaining = store.listObjects("bucket", "cluster-");
        assertEquals(2, remaining.size());
        assertFalse(remaining.contains("cluster-10000000-010100.axbak"));
    }

    @Test
    void backupAndRestoreRoundTrip() {
        var store = new InMemoryBackupStore();
        var backend = new MemoryBackend();
        Datastore ds = new Datastore(backend);
        ds.createDatabase("test", "main");
        backend.put("user:1", "{\"name\":\"Alice\"}".getBytes());
        backend.put("user:2", "{\"name\":\"Bob\"}".getBytes());

        var stamp = new AtomicInteger(1);
        var mgr = new S3SyncManager(ds, store, "bucket", "backups", 60_000L, 10, "",
            () -> 0L, () -> String.format("30000000-0303%02d", stamp.getAndIncrement()));
        assertTrue(mgr.backupNow());
        assertEquals(1, store.listObjects("bucket", "backups/").size());

        Datastore restored = Datastore.memory();
        restored.createDatabase("test", "main");
        var restoreMgr = new S3SyncManager(restored, store, "bucket", "backups",
            60_000L, 10, "", () -> 0L, () -> "x");
        assertTrue(restoreMgr.restoreFromS3());

        var rBackend = backendOf(restored);
        assertTrue(rBackend.get("user:1").isPresent());
        assertEquals("{\"name\":\"Alice\"}",
            new String(rBackend.get("user:1").get(), StandardCharsets.UTF_8));
    }

    @Test
    void encryptedBackupAndRestore() {
        var store = new InMemoryBackupStore();
        var backend = new MemoryBackend();
        Datastore ds = new Datastore(backend);
        ds.createDatabase("test", "main");
        backend.put("k", "v".getBytes());

        var stamp = new AtomicInteger(1);
        var mgr = new S3SyncManager(ds, store, "bucket", "", 60_000L, 10,
            "secret-key", () -> 0L,
            () -> String.format("40000000-0404%02d", stamp.getAndIncrement()));
        assertTrue(mgr.backupNow());

        String key = "cluster-40000000-040401.axbak";
        String text = new String(store.download("bucket", key), StandardCharsets.UTF_8);
        assertTrue(text.startsWith(S3Encryption.ENCRYPTED_HEADER));

        Datastore restored = Datastore.memory();
        restored.createDatabase("test", "main");
        var restoreMgr = new S3SyncManager(restored, store, "bucket", "",
            60_000L, 10, "secret-key", () -> 0L, () -> "x");
        assertTrue(restoreMgr.restoreFromS3());

        Datastore noPw = Datastore.memory();
        noPw.createDatabase("test", "main");
        var noPwMgr = new S3SyncManager(noPw, store, "bucket", "",
            60_000L, 10, "", () -> 0L, () -> "x");
        assertFalse(noPwMgr.restoreFromS3(), "restore sem senha deve falhar");
    }
}