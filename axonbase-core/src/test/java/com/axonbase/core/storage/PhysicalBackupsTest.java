package com.axonbase.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Backup físico com o BackupEngine do RocksDB")
class PhysicalBackupsTest {

    @TempDir
    Path tmp;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static TreeMap<String, String> dump(RocksDbBackend b) {
        TreeMap<String, String> out = new TreeMap<>();
        b.scanEntries("", (k, v) -> {
            out.put(k, new String(v, StandardCharsets.UTF_8));
            return true;
        });
        return out;
    }

    private static void fill(RocksDbBackend b, String prefix, int from, int to) {
        for (int i = from; i < to; i++) {
            b.put(prefix + i, bytes("valor-" + prefix + i));
        }
    }

    @Test
    @DisplayName("completo restaura dados e versões exatamente como estavam")
    void fullRoundTrip() {
        Path live = tmp.resolve("live");
        Path store = tmp.resolve("bk");
        TreeMap<String, String> expected;
        long version;
        int id;
        try (RocksDbBackend b = new RocksDbBackend(live.toString());
             PhysicalBackups pb = PhysicalBackups.open(store)) {
            fill(b, "a", 0, 500);
            b.delete("a7");
            expected = dump(b);
            version = b.currentVersion();
            id = pb.create(b).id();
            fill(b, "z", 0, 100); // depois do backup: não pode aparecer no restore
        }
        Path restored = tmp.resolve("restored");
        PhysicalBackups.restore(store, id, restored);
        try (RocksDbBackend r = new RocksDbBackend(restored.toString())) {
            assertEquals(expected, dump(r));
            assertTrue(r.currentVersion() >= version, "a marca d'água não pode voltar atrás");
            r.put("novo", bytes("x")); // continua gravável, com versão nova maior que as copiadas
            assertTrue(r.currentVersion() > version);
        }
    }

    @Test
    @DisplayName("o manifesto guarda a marca d'água de versão do instante do backup")
    void infoCarriesWatermark() {
        try (RocksDbBackend b = new RocksDbBackend(tmp.resolve("live").toString());
             PhysicalBackups pb = PhysicalBackups.open(tmp.resolve("bk"))) {
            fill(b, "a", 0, 50);
            long mark = b.currentVersion();
            PhysicalBackups.Info info = pb.create(b);
            assertEquals(mark, info.version());
            assertTrue(info.sizeBytes() > 0);
            assertTrue(info.files() > 0);
            assertEquals(List.of(info), pb.list());
        }
    }

    @Test
    @DisplayName("incremental reaproveita os arquivos já copiados e cada backup restaura o seu próprio estado")
    void incrementalSharesFilesAndKeepsEachState() throws IOException {
        Path store = tmp.resolve("bk");
        TreeMap<String, String> s1, s2, s3;
        int id1, id2, id3;
        try (RocksDbBackend b = new RocksDbBackend(tmp.resolve("live").toString());
             PhysicalBackups pb = PhysicalBackups.open(store)) {
            fill(b, "a", 0, 2000);
            s1 = dump(b);
            id1 = pb.create(b).id();
            long afterFirst = bytesUnder(store);
            fill(b, "b", 0, 50);
            b.delete("a1");
            s2 = dump(b);
            id2 = pb.create(b).id();
            long afterSecond = bytesUnder(store);
            fill(b, "c", 0, 50);
            s3 = dump(b);
            id3 = pb.create(b).id();
            assertTrue(afterSecond - afterFirst < afterFirst,
                "o segundo backup não pode copiar tudo de novo: " + afterFirst + " -> " + afterSecond);
            assertEquals(3, pb.list().size());
        }
        for (Object[] c : new Object[][] {{id1, s1}, {id2, s2}, {id3, s3}}) {
            Path out = tmp.resolve("r" + c[0]);
            PhysicalBackups.restore(store, (Integer) c[0], out);
            try (RocksDbBackend r = new RocksDbBackend(out.toString())) {
                assertEquals(c[1], dump(r), "backup " + c[0]);
            }
        }
    }

    @Test
    @DisplayName("verifica a integridade e detecta arquivo adulterado")
    void verifyDetectsCorruption() throws IOException {
        Path store = tmp.resolve("bk");
        int id;
        try (RocksDbBackend b = new RocksDbBackend(tmp.resolve("live").toString());
             PhysicalBackups pb = PhysicalBackups.open(store)) {
            fill(b, "a", 0, 3000);
            id = pb.create(b).id();
            assertTrue(pb.verify(id));
        }
        Path sst = largestFile(store.resolve("shared_checksum"), store.resolve("shared"), store.resolve("private"));
        try (RandomAccessFile f = new RandomAccessFile(sst.toFile(), "rw")) {
            f.seek(Math.max(0, f.length() / 2));
            int old = f.read();
            f.seek(Math.max(0, f.length() / 2));
            f.write(old ^ 0x5A);
        }
        try (PhysicalBackups pb = PhysicalBackups.open(store)) {
            assertFalse(pb.verify(id), "o arquivo adulterado precisa reprovar");
        }
        assertThrows(PhysicalBackups.BackupFailedException.class,
            () -> PhysicalBackups.restore(store, id, tmp.resolve("nao-deve-existir")));
        assertFalse(Files.exists(tmp.resolve("nao-deve-existir")), "restore falho não deixa diretório de destino");
    }

    @Test
    @DisplayName("retenção apaga os mais antigos e os que ficam continuam restaurando")
    void purgeKeepsNewest() {
        Path store = tmp.resolve("bk");
        TreeMap<String, String> last = null;
        int lastId = -1;
        try (RocksDbBackend b = new RocksDbBackend(tmp.resolve("live").toString());
             PhysicalBackups pb = PhysicalBackups.open(store)) {
            for (int i = 0; i < 5; i++) {
                fill(b, "r" + i + "-", 0, 200);
                last = dump(b);
                lastId = pb.create(b).id();
            }
            pb.purgeKeeping(2);
            List<Integer> ids = new ArrayList<>();
            pb.list().forEach(i -> ids.add(i.id()));
            assertEquals(2, ids.size());
            assertTrue(ids.contains(lastId));
        }
        Path out = tmp.resolve("out");
        PhysicalBackups.restore(store, lastId, out);
        try (RocksDbBackend r = new RocksDbBackend(out.toString())) {
            assertEquals(last, dump(r));
        }
    }

    @Test
    @DisplayName("restore recusa um destino que já tem arquivos")
    void restoreRefusesNonEmptyTarget() throws IOException {
        Path store = tmp.resolve("bk");
        int id;
        try (RocksDbBackend b = new RocksDbBackend(tmp.resolve("live").toString());
             PhysicalBackups pb = PhysicalBackups.open(store)) {
            fill(b, "a", 0, 10);
            id = pb.create(b).id();
        }
        Path target = tmp.resolve("ocupado");
        Files.createDirectories(target);
        Files.writeString(target.resolve("arquivo"), "x");
        assertThrows(PhysicalBackups.BackupFailedException.class, () -> PhysicalBackups.restore(store, id, target));
        assertEquals("x", Files.readString(target.resolve("arquivo")));
    }

    @Test
    @DisplayName("troca atômica: restore válido substitui a base e guarda a anterior")
    void atomicSwapReplacesLive() throws IOException {
        Path store = tmp.resolve("bk");
        Path live = tmp.resolve("live");
        TreeMap<String, String> atBackup;
        int id;
        try (RocksDbBackend b = new RocksDbBackend(live.toString());
             PhysicalBackups pb = PhysicalBackups.open(store)) {
            fill(b, "a", 0, 300);
            atBackup = dump(b);
            id = pb.create(b).id();
            fill(b, "depois", 0, 300);
        }
        Path previous = PhysicalBackups.restoreInPlace(store, id, live);
        assertTrue(Files.exists(previous), "a base anterior fica guardada");
        try (RocksDbBackend r = new RocksDbBackend(live.toString())) {
            assertEquals(atBackup, dump(r));
        }
        try (RocksDbBackend old = new RocksDbBackend(previous.toString())) {
            assertTrue(dump(old).containsKey("depois0"), "a base anterior está intacta");
        }
        assertFalse(Files.exists(live.resolve(".restoring")), "sem diretório temporário sobrando");
    }

    @Test
    @DisplayName("restore que falha no meio não toca na base original")
    void failedSwapLeavesLiveIntact() throws IOException {
        Path store = tmp.resolve("bk");
        Path live = tmp.resolve("live");
        TreeMap<String, String> before;
        int id;
        try (RocksDbBackend b = new RocksDbBackend(live.toString());
             PhysicalBackups pb = PhysicalBackups.open(store)) {
            fill(b, "a", 0, 3000);
            id = pb.create(b).id();
            fill(b, "extra", 0, 100);
            before = dump(b);
        }
        Path sst = largestFile(store.resolve("shared_checksum"), store.resolve("shared"), store.resolve("private"));
        try (RandomAccessFile f = new RandomAccessFile(sst.toFile(), "rw")) {
            f.seek(f.length() / 2);
            f.write(0x7F);
            f.write(0x01);
        }
        assertThrows(PhysicalBackups.BackupFailedException.class, () -> PhysicalBackups.restoreInPlace(store, id, live));
        try (RocksDbBackend r = new RocksDbBackend(live.toString())) {
            assertEquals(before, dump(r));
        }
        assertFalse(Files.exists(live.resolve(".restoring")), "o temporário do restore falho é removido");
    }

    @Test
    @DisplayName("troca interrompida na fase 2 (restaurada pela metade) volta para a base anterior ao abrir")
    void interruptedSwapInPhaseTwoRollsBack() throws IOException {
        Path live = tmp.resolve("live");
        try (RocksDbBackend b = new RocksDbBackend(live.toString())) {
            fill(b, "a", 0, 10);
        }
        // simulação: a anterior inteira foi guardada, a restaurada foi movida só em parte
        Path replaced = live.resolve(".replaced-1");
        Files.createDirectories(replaced);
        try (Stream<Path> s = Files.list(live)) {
            for (Path p : (Iterable<Path>) s.filter(x -> !x.getFileName().toString().startsWith("."))::iterator) {
                Files.move(p, replaced.resolve(p.getFileName()));
            }
        }
        Files.writeString(live.resolve("000099.sst"), "pedaco-da-restaurada");
        Files.writeString(live.resolve(".swap"), "2:.replaced-1");
        Files.createDirectories(live.resolve(".restoring"));

        try (RocksDbBackend r = new RocksDbBackend(live.toString())) {
            assertEquals(10, dump(r).size(), "a base anterior voltou inteira");
        }
        assertFalse(Files.exists(live.resolve("000099.sst")), "o pedaço da restaurada foi descartado");
        assertFalse(Files.exists(live.resolve(".swap")));
        assertFalse(Files.exists(live.resolve(".restoring")));
        assertFalse(Files.exists(replaced));
    }

    @Test
    @DisplayName("troca interrompida na fase 1 (anterior movida em parte) volta para a base anterior")
    void interruptedSwapInPhaseOneRollsBack() throws IOException {
        Path live = tmp.resolve("live");
        try (RocksDbBackend b = new RocksDbBackend(live.toString())) {
            fill(b, "a", 0, 10);
        }
        Path replaced = live.resolve(".replaced-1");
        Files.createDirectories(replaced);
        try (Stream<Path> s = Files.list(live)) {
            Path one = s.filter(x -> x.getFileName().toString().endsWith(".log")).findFirst().orElseThrow();
            Files.move(one, replaced.resolve(one.getFileName())); // só um arquivo saiu
        }
        Files.writeString(live.resolve(".swap"), "1:.replaced-1");
        assertTrue(PhysicalBackups.recoverInterruptedSwap(live));
        try (RocksDbBackend r = new RocksDbBackend(live.toString())) {
            assertEquals(10, dump(r).size());
        }
        assertFalse(PhysicalBackups.recoverInterruptedSwap(live), "nada a recuperar sem marcador");
    }

    @Test
    @DisplayName("o diretório de dados em si nunca é renomeado (raiz de volume montado)")
    void swapNeverRenamesTheDataDirectory() throws IOException {
        Path store = tmp.resolve("bk");
        Path live = tmp.resolve("live");
        int id;
        try (RocksDbBackend b = new RocksDbBackend(live.toString());
             PhysicalBackups pb = PhysicalBackups.open(store)) {
            fill(b, "a", 0, 20);
            id = pb.create(b).id();
        }
        Object before = Files.readAttributes(live, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        PhysicalBackups.restoreInPlace(store, id, live);
        Object after = Files.readAttributes(live, java.nio.file.attribute.BasicFileAttributes.class).fileKey();
        assertEquals(before, after, "é o mesmo diretório (mesmo inode), só o conteúdo mudou");
    }

    private static long bytesUnder(Path dir) throws IOException {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
        }
    }

    private static Path largestFile(Path... dirs) throws IOException {
        Path best = null;
        long size = -1;
        for (Path d : dirs) {
            if (!Files.isDirectory(d)) {
                continue;
            }
            try (Stream<Path> s = Files.list(d)) {
                for (Path p : (Iterable<Path>) s::iterator) {
                    if (Files.isRegularFile(p) && p.toFile().length() > size) {
                        size = p.toFile().length();
                        best = p;
                    }
                }
            }
        }
        if (best == null) {
            throw new IllegalStateException("nenhum arquivo de dados no backup");
        }
        return best;
    }
}
