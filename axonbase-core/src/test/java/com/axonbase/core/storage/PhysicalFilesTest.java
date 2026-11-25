package com.axonbase.core.storage;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Arquivos do backup físico (listar, ler, gravar, importar)")
class PhysicalFilesTest {

    @TempDir
    Path tmp;

    private static TreeMap<String, String> dump(RocksDbBackend b) {
        TreeMap<String, String> out = new TreeMap<>();
        b.scanEntries("", (k, v) -> {
            out.put(k, new String(v, StandardCharsets.UTF_8));
            return true;
        });
        return out;
    }

    private static void fill(RocksDbBackend b, String prefix, int n) {
        for (int i = 0; i < n; i++) {
            b.put(prefix + i, ("v" + prefix + i).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Cria dois backups e devolve (diretório, ids). */
    private int[] twoBackups(Path store) {
        try (RocksDbBackend b = new RocksDbBackend(tmp.resolve("live-" + System.nanoTime()).toString());
             PhysicalBackups pb = PhysicalBackups.open(store)) {
            fill(b, "a", 2000);
            int first = pb.create(b).id();
            fill(b, "b", 300);
            int second = pb.create(b).id();
            return new int[] {first, second};
        }
    }

    @Test
    @DisplayName("lista os arquivos do backup com o manifesto por último e marca os compartilhados")
    void listsFilesWithMetaLast() throws IOException {
        Path store = tmp.resolve("bk");
        int[] ids = twoBackups(store);
        List<PhysicalFiles.Entry> files = PhysicalFiles.list(store, ids[1]);
        assertFalse(files.isEmpty());
        assertEquals("meta/" + ids[1], files.get(files.size() - 1).path(), "o manifesto é o último");
        assertTrue(files.stream().anyMatch(PhysicalFiles.Entry::shared), "há arquivos SST compartilhados");
        assertTrue(files.stream().anyMatch(f -> f.path().startsWith("private/" + ids[1] + "/")));
        assertFalse(files.stream().anyMatch(f -> f.path().startsWith("private/" + ids[0] + "/")),
            "não traz arquivos exclusivos de outro backup");
        for (PhysicalFiles.Entry f : files) {
            assertEquals(Files.size(store.resolve(f.path())), f.sizeBytes(), f.path());
        }
    }

    @Test
    @DisplayName("o segundo backup reaproveita arquivos compartilhados do primeiro")
    void sharedFilesAreReused() {
        Path store = tmp.resolve("bk");
        int[] ids = twoBackups(store);
        List<String> first = PhysicalFiles.list(store, ids[0]).stream().map(PhysicalFiles.Entry::path).toList();
        List<String> second = PhysicalFiles.list(store, ids[1]).stream().map(PhysicalFiles.Entry::path).toList();
        assertTrue(second.stream().anyMatch(p -> p.startsWith("shared_checksum/") && first.contains(p)),
            "o incremental reaproveita pelo menos um SST do primeiro");
    }

    @Test
    @DisplayName("recusa caminhos fora de meta, private e shared_checksum, com .. ou absolutos")
    void rejectsBadPaths() {
        Path store = tmp.resolve("bk");
        for (String bad : new String[] {"../x", "meta/../../x", "/etc/passwd", "imports/a/meta/1", "outro/arquivo",
            "meta", "", "meta/1/../../..", "shared_checksum\\x", "meta/1\u0000"}) {
            assertThrows(IllegalArgumentException.class, () -> PhysicalFiles.resolve(store, null, bad), bad);
        }
        assertEquals(store.resolve("meta/3"), PhysicalFiles.resolve(store, null, "meta/3"));
        assertEquals(store.resolve("imports/orig/meta/3"), PhysicalFiles.resolve(store, "orig", "meta/3"));
    }

    @Test
    @DisplayName("nome de origem só aceita letras, números, ponto, hífen e sublinhado")
    void validatesSource() {
        for (String bad : new String[] {"..", ".", "a/b", "a b", "", "x".repeat(200), "../x"}) {
            assertThrows(IllegalArgumentException.class, () -> PhysicalFiles.importRoot(tmp, bad), bad);
        }
        assertEquals(tmp.resolve("imports/cluster-1_a.b"), PhysicalFiles.importRoot(tmp, "cluster-1_a.b"));
        assertEquals(tmp, PhysicalFiles.importRoot(tmp, null));
    }

    @Test
    @DisplayName("grava de forma atômica e aceita de novo um arquivo compartilhado idêntico sem regravar")
    void writesAtomically() throws IOException {
        Path store = tmp.resolve("bk");
        byte[] data = "conteudo".getBytes(StandardCharsets.UTF_8);
        String rel = "shared_checksum/000001_abc_8.sst";
        long written = PhysicalFiles.write(store, "orig", rel, new ByteArrayInputStream(data), data.length);
        assertEquals(data.length, written);
        Path target = store.resolve("imports/orig/" + rel);
        assertEquals("conteudo", Files.readString(target));
        long modified = Files.getLastModifiedTime(target).toMillis();
        assertEquals(data.length, PhysicalFiles.write(store, "orig", rel, new ByteArrayInputStream(data), data.length));
        assertEquals(modified, Files.getLastModifiedTime(target).toMillis(), "arquivo compartilhado idêntico não é regravado");
        try (var s = Files.list(target.getParent())) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")), "sem temporários sobrando");
        }
    }

    @Test
    @DisplayName("tamanho declarado diferente do recebido recusa e não deixa arquivo")
    void sizeMismatchLeavesNothing() {
        Path store = tmp.resolve("bk");
        byte[] data = "abc".getBytes(StandardCharsets.UTF_8);
        assertThrows(IOException.class,
            () -> PhysicalFiles.write(store, "orig", "meta/9", new ByteArrayInputStream(data), 99));
        assertFalse(Files.exists(store.resolve("imports/orig/meta/9")));
    }

    @Test
    @DisplayName("copiar todos os arquivos para uma origem e restaurar dela dá o estado do backup")
    void importAndRestoreFromSource() throws IOException {
        Path store = tmp.resolve("bk");
        int[] ids = twoBackups(store);
        Path other = tmp.resolve("outro-motor");
        for (PhysicalFiles.Entry f : PhysicalFiles.list(store, ids[1])) {
            try (var in = Files.newInputStream(store.resolve(f.path()))) {
                PhysicalFiles.write(other, "origem-1", f.path(), in, f.sizeBytes());
            }
        }
        Path restored = tmp.resolve("restaurado");
        PhysicalBackups.restore(PhysicalFiles.importRoot(other, "origem-1"), ids[1], restored);
        TreeMap<String, String> expected;
        Path again = tmp.resolve("direto");
        PhysicalBackups.restore(store, ids[1], again);
        try (RocksDbBackend a = new RocksDbBackend(restored.toString());
             RocksDbBackend b = new RocksDbBackend(again.toString())) {
            expected = dump(b);
            assertEquals(expected, dump(a));
            assertTrue(expected.containsKey("b299"));
        }
        PhysicalFiles.deleteImport(other, "origem-1");
        assertFalse(Files.exists(other.resolve("imports/origem-1")));
        List<Path> leftovers = new ArrayList<>();
        try (var s = Files.list(other)) {
            s.forEach(leftovers::add);
        }
        assertFalse(leftovers.stream().anyMatch(p -> p.getFileName().toString().equals("origem-1")));
    }
}
