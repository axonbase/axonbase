package com.axonbase.server;

import com.axonbase.common.Messages;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.stream.Stream;

/**
 * Onde ficam o estado do consenso e os truststores, derivados do caminho da base.
 *
 * <p>Até a 1.0.0-rc1 os dois ficavam ao lado do diretório de dados ({@code /data.raft} e
 * {@code /data.truststores} na imagem Docker), fora do volume montado em {@code /data}. Agora ficam dentro do
 * diretório de dados, em nomes que começam com ponto (a troca de base na restauração não os move). O que já estiver
 * no local antigo continua valendo: o estado do Raft é usado onde está, e os truststores são copiados para dentro
 * sem apagar o original.</p>
 */
final class StatePaths {

    private static final String ROCKSDB = "rocksdb:";

    private StatePaths() {
    }

    private static boolean inMemory(String path) {
        return path == null || path.isBlank() || path.equals("memory");
    }

    private static Path dataDir(String path) {
        return Path.of(path.startsWith(ROCKSDB) ? path.substring(ROCKSDB.length()) : path);
    }

    /**
     * Diretório do estado do Raft.
     *
     * @throws IllegalStateException se há estado no local antigo e no novo: escolher um deles poderia fazer o nó
     *                               votar duas vezes ou perder o registro do consenso
     */
    static Path raftDir(String path) {
        // o local antigo é o texto do caminho mais o sufixo, inclusive com o prefixo rocksdb:
        Path old = Path.of(path + ".raft");
        if (inMemory(path)) {
            return old;
        }
        Path inside = dataDir(path).resolve(".raft");
        if (same(old, inside) || !hasFiles(old)) {
            return inside;
        }
        if (hasFiles(inside)) {
            throw new IllegalStateException(Messages.get("server_raft_state_ambiguous", old, inside));
        }
        return old;
    }

    /** {@code true} quando o estado do Raft em uso está no local antigo, fora do diretório de dados. */
    static boolean outsideDataDir(String path, Path raftDir) {
        return !inMemory(path) && !same(raftDir, dataDir(path).resolve(".raft"));
    }

    /** Uma base que já existe sem estado do Raft em lugar nenhum, como depois de recriar o contêiner da rc1. */
    static boolean dataWithoutRaftState(String path) {
        return !inMemory(path) && Files.isRegularFile(dataDir(path).resolve("CURRENT"))
            && !hasFiles(raftDir(path));
    }

    /** {@code true} quando {@code AXON_PATH} foi informado e a linha de comando abriu outra base. */
    static boolean envPathIgnored(String envPath, String effectivePath) {
        return envPath != null && !envPath.isBlank() && !envPath.equals(effectivePath);
    }

    /** Diretório onde os truststores enviados pela API são gravados. */
    static Path truststoreDir(String path) {
        return inMemory(path)
            ? Path.of(System.getProperty("java.io.tmpdir"), "axonbase-truststores")
            : dataDir(path).resolve(".truststores");
    }

    private static Path oldTruststoreDir(String path) {
        if (path.startsWith(ROCKSDB)) {
            Path data = dataDir(path);
            Path name = data.getFileName();
            return name == null ? null : data.resolveSibling(name + ".truststores");
        }
        return Path.of(path + ".truststores");
    }

    /** Copia para dentro do diretório de dados os truststores do local antigo que ainda não estão lá. */
    static void adoptTruststores(String path) {
        if (inMemory(path)) {
            return;
        }
        Path old = oldTruststoreDir(path);
        Path target = truststoreDir(path);
        if (old == null || same(old, target) || !Files.isDirectory(old)) {
            return;
        }
        try (Stream<Path> files = Files.list(old)) {
            for (Path file : files.filter(f -> f.getFileName().toString().endsWith(".jks")).toList()) {
                Path copy = target.resolve(file.getFileName());
                if (Files.exists(copy)) {
                    continue;
                }
                Files.createDirectories(target);
                // temporário e renomeação: uma queda no meio não deixa um JKS pela metade no destino
                Path partial = Files.createTempFile(target, "adocao-", ".jks.tmp");
                try {
                    Files.copy(file, partial, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(partial, copy, StandardCopyOption.ATOMIC_MOVE);
                } finally {
                    Files.deleteIfExists(partial);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** O caminho gravado no plano de controle; se o arquivo sumiu, o de mesmo nome dentro do diretório de dados. */
    static String locateTruststore(String path, String stored) {
        if (inMemory(path) || Files.exists(Path.of(stored))) {
            return stored;
        }
        Path name = Path.of(stored).getFileName();
        if (name == null) {
            return stored;
        }
        Path inside = truststoreDir(path).resolve(name);
        return Files.exists(inside) ? inside.toString() : stored;
    }

    private static boolean same(Path a, Path b) {
        return a.toAbsolutePath().normalize().equals(b.toAbsolutePath().normalize());
    }

    private static boolean hasFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(dir, 3)) {
            return walk.anyMatch(Files::isRegularFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
