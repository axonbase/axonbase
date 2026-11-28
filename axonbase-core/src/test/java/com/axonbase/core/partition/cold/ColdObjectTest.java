package com.axonbase.core.partition.cold;

import com.axonbase.common.AxonError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Formato do objeto frio, filtro de Bloom, armazenamento em diretório, cache e bloqueios. */
@DisplayName("Camada fria: objetos")
class ColdObjectTest {

    @TempDir
    Path tmp;

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String key(int i) {
        return "s" + String.format("%05d", i);
    }

    private Path write(String name, int rows) throws IOException {
        Path target = tmp.resolve(name);
        try (ColdObject.Writer writer = ColdObject.writer(target)) {
            for (int i = 0; i < rows; i++) {
                writer.add(key(i), bytes("valor " + i));
            }
            writer.finish();
        }
        return target;
    }

    private ColdObject.Reader read(Path compressed) throws IOException {
        Path inflated = Files.createTempFile(tmp, "obj", ".axc");
        Files.delete(inflated);
        ColdObject.inflate(compressed, inflated);
        return ColdObject.open(inflated);
    }

    @Test
    @DisplayName("leitura por chave e por prefixo, nas bordas das amostras")
    void roundTrip_getAndScan() throws IOException {
        ColdObject.Reader reader = read(write("o", 500));

        assertEquals(500, reader.rows());
        for (int i : new int[] {0, 1, 63, 64, 65, 127, 128, 499}) {
            assertArrayEquals(bytes("valor " + i), reader.get(key(i)).orElseThrow(), "linha " + i);
        }
        assertTrue(reader.get("s99999").isEmpty());
        assertTrue(reader.get("a").isEmpty());
        assertTrue(reader.get(key(10) + "x").isEmpty());
        List<String> scanned = new ArrayList<>();
        assertTrue(reader.scan("s001", (k, v) -> scanned.add(k)));
        assertEquals(100, scanned.size());
        assertEquals(key(100), scanned.get(0));
        List<String> first = new ArrayList<>();
        assertFalse(reader.scan("", (k, v) -> {
            first.add(k);
            return first.size() < 3;
        }));
        assertEquals(3, first.size());
    }

    @Test
    @DisplayName("objeto vazio é válido, e chave fora de ordem é recusada")
    void emptyAndOrdering() throws IOException {
        assertEquals(0, read(write("vazio", 0)).rows());
        try (ColdObject.Writer writer = ColdObject.writer(tmp.resolve("desordem"))) {
            writer.add("s2", bytes("b"));
            assertThrows(IllegalStateException.class, () -> writer.add("s1", bytes("a")));
        }
    }

    @Test
    @DisplayName("um byte trocado ou um objeto cortado é detectado")
    void corruption_detected() throws IOException {
        Path compressed = write("o", 200);
        Path inflated = tmp.resolve("o.axc");
        ColdObject.inflate(compressed, inflated);
        byte[] content = Files.readAllBytes(inflated);
        content[content.length / 2] ^= 1;
        Files.write(tmp.resolve("trocado"), content);
        Files.write(tmp.resolve("cortado"), java.util.Arrays.copyOf(content, content.length / 2));
        byte[] packed = Files.readAllBytes(compressed);
        Files.write(tmp.resolve("gz-cortado"), java.util.Arrays.copyOf(packed, packed.length / 2));

        assertThrows(ColdObject.CorruptException.class, () -> ColdObject.open(tmp.resolve("trocado")));
        assertThrows(ColdObject.CorruptException.class, () -> ColdObject.open(tmp.resolve("cortado")));
        assertThrows(ColdObject.CorruptException.class,
            () -> ColdObject.inflate(tmp.resolve("gz-cortado"), tmp.resolve("saida")));
    }

    @Test
    @DisplayName("o filtro de Bloom nunca nega uma chave presente e raramente aceita uma ausente")
    void bloom_noFalseNegatives() {
        BloomFilter filter = BloomFilter.sizedFor(10_000);
        for (int i = 0; i < 10_000; i++) {
            filter.add(key(i));
        }
        BloomFilter copy = BloomFilter.decode(filter.encode());
        int falsePositives = 0;
        for (int i = 0; i < 10_000; i++) {
            assertTrue(copy.mightContain(key(i)));
            falsePositives += copy.mightContain("outra" + i) ? 1 : 0;
        }
        assertTrue(falsePositives < 50, "falsos positivos: " + falsePositives);
        assertThrows(IllegalArgumentException.class, () -> BloomFilter.decode(new byte[3]));
    }

    @Test
    @DisplayName("o diretório guarda, lista e apaga objetos, sem deixar a chave escapar dele")
    void fileSystemStore() throws IOException {
        ColdStore store = new FileSystemColdStore(tmp.resolve("store"));
        Path source = Files.write(tmp.resolve("origem"), bytes("dados"));
        store.upload("parts/n/d/t/1.axc", source);
        store.write("holds/a.json", bytes("{}"));

        assertEquals(List.of("parts/n/d/t/1.axc"), store.list("parts/"));
        assertTrue(store.download("parts/n/d/t/1.axc", tmp.resolve("copia")));
        assertEquals("dados", Files.readString(tmp.resolve("copia")));
        assertFalse(store.download("parts/nao", tmp.resolve("copia2")));
        assertTrue(store.read("holds/nao.json").isEmpty());
        store.delete("parts/n/d/t/1.axc");
        store.delete("parts/n/d/t/1.axc");
        assertTrue(store.list("parts/").isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.write("../fora", bytes("x")));
    }

    @Test
    @DisplayName("o cache baixa uma vez, descarta o mais antigo quando passa do teto e falha alto sem o objeto")
    void cache_reuseEvictAndMissing() throws IOException {
        ColdStore store = new FileSystemColdStore(tmp.resolve("store"));
        store.upload("parts/a", write("a", 100));
        store.upload("parts/b", write("b", 100));
        try (ColdCache cache = new ColdCache(store, tmp.resolve("cache"), 3_000)) {
            cache.open("parts/a");
            cache.open("parts/a");
            assertEquals(1, cache.downloads());
            ColdObject.Reader a = cache.open("parts/a");
            cache.open("parts/b");
            cache.open("parts/a");
            assertEquals(3, cache.downloads());
            assertTrue(a.get(key(5)).isPresent());

            AxonError missing = assertThrows(AxonError.class, () -> cache.open("parts/nao"));
            assertTrue(missing.getMessage().contains("parts/nao"));
            store.write("parts/lixo", bytes("não é gzip"));
            assertThrows(AxonError.class, () -> cache.open("parts/lixo"));
        }
    }

    @Test
    @DisplayName("bloqueios somam os objetos presos, vencem e podem ser soltos")
    void holds_unionExpireRelease() throws IOException {
        ColdHolds holds = new ColdHolds(new FileSystemColdStore(tmp.resolve("store")));
        holds.put("backup-a", Set.of("parts/1", "parts/2"), ColdHolds.NEVER);
        holds.put("backup-b", Set.of("parts/3"), 1_000);

        assertEquals(Set.of("parts/1", "parts/2", "parts/3"), holds.held(500));
        assertEquals(Set.of("parts/1", "parts/2"), holds.held(2_000));
        assertEquals(List.of("backup-a"), holds.names("backup-"));
        holds.release("backup-a");
        assertTrue(holds.held(2_000).isEmpty());
    }
}
