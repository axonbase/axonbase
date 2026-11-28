package com.axonbase.core;

import com.axonbase.core.engine.Datastore;
import com.axonbase.core.storage.KvBackend;
import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Restauração de arquivo v2 que para no meio fica marcada")
class RestoreMarkerTest {

    @TempDir
    Path dir;

    /** Recusa a gravação dos registros enquanto cheio, como um disco que acaba no meio da restauração. */
    private static final class FullDisk implements KvBackend {
        private final MemoryBackend inner = new MemoryBackend();
        boolean full;

        @Override
        public Optional<byte[]> get(String key) {
            return inner.get(key);
        }

        @Override
        public void put(String key, byte[] value) {
            if (full && key.startsWith("app\u0000main\u0000p\u0000")) {
                throw new IllegalStateException("disco cheio");
            }
            inner.put(key, value);
        }

        @Override
        public boolean putIfAbsent(String key, byte[] value) {
            return inner.putIfAbsent(key, value);
        }

        @Override
        public boolean delete(String key) {
            return inner.delete(key);
        }

        @Override
        public List<String> keysWithPrefix(String prefix) {
            return inner.keysWithPrefix(prefix);
        }
    }

    private Path backupFile() throws IOException {
        Datastore source = Datastore.memory();
        source.createDatabase("app", "main");
        source.execute("CREATE p:1 CONTENT {n: 1}", new Session("app", "main"), null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        source.backupTo(out, 0);
        return Files.write(dir.resolve("completo.axbak"), out.toByteArray());
    }

    @Test
    @DisplayName("a marca fica quando a aplicação falha e sai quando a restauração termina")
    void interruptedFileRestore_isMarked() throws IOException {
        Path file = backupFile();
        FullDisk disk = new FullDisk();
        Datastore target = new Datastore(disk);
        disk.full = true;

        assertThrows(RuntimeException.class, () -> target.restoreFile(file));

        assertTrue(target.restoreInterrupted(), "a base pela metade precisa ficar marcada");

        disk.full = false;
        target.restoreFile(file);

        assertFalse(target.restoreInterrupted());
    }
}
