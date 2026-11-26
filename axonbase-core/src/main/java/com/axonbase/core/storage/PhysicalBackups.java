package com.axonbase.core.storage;

import com.axonbase.core.hlc.HlcTimestamp;
import org.rocksdb.BackupEngine;
import org.rocksdb.BackupEngineOptions;
import org.rocksdb.BackupInfo;
import org.rocksdb.Env;
import org.rocksdb.RestoreOptions;
import org.rocksdb.RocksDBException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Backup físico com o {@code BackupEngine} do RocksDB. Cada backup copia os arquivos SST (Sorted String Table)
 * e o log de escrita de um instante consistente; os arquivos que já estão no diretório de backup são
 * reaproveitados, então o backup seguinte só paga pelo que mudou. Qualquer backup restaura sozinho, sem montar
 * cadeia. O backup lógico em texto (o {@code .axbak}) continua existindo para portabilidade entre versões.
 *
 * <p>O diretório de backup é do {@code BackupEngine} (subpastas {@code meta}, {@code private} e
 * {@code shared_checksum}): não escreva nele por fora.</p>
 */
public final class PhysicalBackups implements AutoCloseable {

    /**
     * Um backup do diretório. {@code version} é a marca d'água de versão do instante do backup; {@code watermark},
     * a posição HLC lida antes de copiar (o backup contém todos os lotes até ela), é
     * {@link CommitWatermark#NONE} em backups anteriores à 0.11, que não servem de base para a restauração a um
     * instante.
     */
    public record Info(int id, long timestampSeconds, long sizeBytes, int files, long version,
                       CommitWatermark watermark) {
    }

    /** Falha de backup físico, com a causa original do RocksDB ou do sistema de arquivos. */
    public static final class BackupFailedException extends RuntimeException {
        public BackupFailedException(String message, Throwable cause) {
            super(message, cause);
        }

        public BackupFailedException(String message) {
            super(message);
        }
    }

    private static final String META_PREFIX = "axon-version=";
    private static final String META_STAMP = "axon-hlc=";
    private static final String META_INDEX = "axon-idx=";
    private static final String META_TIMELINE = "axon-tl=";

    static {
        // ferramentas que só restauram (sem abrir um RocksDbBackend antes) precisam da biblioteca nativa carregada
        org.rocksdb.RocksDB.loadLibrary();
    }

    private final BackupEngineOptions options;
    private final BackupEngine engine;

    private PhysicalBackups(BackupEngineOptions options, BackupEngine engine) {
        this.options = options;
        this.engine = engine;
    }

    /** Abre (criando se preciso) o diretório de backup. */
    public static PhysicalBackups open(Path backupDir) {
        BackupEngineOptions opts = null;
        try {
            Files.createDirectories(backupDir);
            opts = new BackupEngineOptions(backupDir.toAbsolutePath().toString())
                .setShareTableFiles(true)
                .setShareFilesWithChecksum(true)
                .setBackupLogFiles(true)
                .setSync(true);
            return new PhysicalBackups(opts, BackupEngine.open(Env.getDefault(), opts));
        } catch (RocksDBException | IOException e) {
            if (opts != null) {
                opts.close();
            }
            throw new BackupFailedException("não foi possível abrir o diretório de backup " + backupDir, e);
        }
    }

    /** Cria um backup do banco em uso, sem pará-lo: descarrega o memtable e copia os arquivos. */
    public Info create(RocksDbBackend backend) {
        long version = backend.currentVersion();
        // antes de copiar: o backup contém tudo até esta marca
        CommitWatermark mark = backend.watermark();
        String metadata = META_PREFIX + version + ";" + META_STAMP + mark.stamp().physical() + "."
            + mark.stamp().logical() + ";" + META_INDEX + mark.index() + ";" + META_TIMELINE + mark.timeline();
        try {
            backend.createPhysicalBackup(engine, metadata);
        } catch (RocksDBException e) {
            throw new BackupFailedException("falha ao criar o backup físico", e);
        }
        List<Info> all = list();
        return all.get(all.size() - 1);
    }

    /** Lista os backups, do mais antigo ao mais novo. */
    public List<Info> list() {
        List<Info> out = new ArrayList<>();
        for (BackupInfo b : engine.getBackupInfo()) {
            out.add(new Info(b.backupId(), b.timestamp(), b.size(), b.numberFiles(), parseVersion(b.appMetadata()),
                parseWatermark(b.appMetadata())));
        }
        out.sort(Comparator.comparingInt(Info::id));
        return out;
    }

    /** Confere só se o RocksDB não marcou o backup como corrompido (metadados). Não vê um byte trocado. */
    public boolean verifyQuick(int id) {
        for (int bad : engine.getCorruptedBackups()) {
            if (bad == id) {
                return false;
            }
        }
        return engine.getBackupInfo().stream().anyMatch(b -> b.backupId() == id);
    }

    /**
     * Verificação completa: restaura o backup num diretório temporário (o RocksDB confere o checksum de cada
     * arquivo ao copiar) e abre o banco. Custa o tamanho do backup em disco e em leitura.
     */
    public boolean verify(int id) {
        if (!verifyQuick(id)) {
            return false;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempDirectory("axon-verify-");
            Path target = tmp.resolve("db");
            restoreFromDir(backupDirOf(), id, target);
            validateOpens(target);
            return true;
        } catch (IOException | BackupFailedException e) {
            return false;
        } finally {
            deleteQuietly(tmp);
        }
    }

    /** Mantém só os {@code keep} backups mais novos e apaga os arquivos que mais nenhum backup usa. */
    public void purgeKeeping(int keep) {
        try {
            engine.purgeOldBackups(keep);
            engine.garbageCollect();
        } catch (RocksDBException e) {
            throw new BackupFailedException("falha ao aplicar a retenção", e);
        }
    }

    /** Apaga um backup. Os arquivos compartilhados com outros backups ficam. */
    public void delete(int id) {
        try {
            engine.deleteBackup(id);
        } catch (RocksDBException e) {
            throw new BackupFailedException("falha ao apagar o backup " + id, e);
        }
    }

    @Override
    public void close() {
        engine.close();
        options.close();
    }

    private Path backupDirOf() {
        return Path.of(options.backupDir());
    }

    // ---- restore ----

    /**
     * Restaura o backup {@code id} do diretório {@code backupDir} num diretório novo. O destino não pode ter
     * arquivos; se a restauração falhar (por exemplo checksum), nada fica no destino.
     */
    public static void restore(Path backupDir, int id, Path target) {
        restoreFromDir(backupDir, id, target);
    }

    private static void restoreFromDir(Path backupDir, int id, Path target) {
        try {
            if (Files.exists(target)) {
                try (Stream<Path> s = Files.list(target)) {
                    if (s.findAny().isPresent()) {
                        throw new BackupFailedException("o destino do restore não está vazio: " + target);
                    }
                }
            }
        } catch (IOException e) {
            throw new BackupFailedException("não foi possível ler o destino " + target, e);
        }
        boolean created = !Files.exists(target);
        try (BackupEngineOptions opts = new BackupEngineOptions(backupDir.toAbsolutePath().toString());
             BackupEngine eng = BackupEngine.open(Env.getDefault(), opts);
             RestoreOptions ro = new RestoreOptions(false)) {
            Files.createDirectories(target);
            String dir = target.toAbsolutePath().toString();
            eng.restoreDbFromBackup(id, dir, dir, ro);
        } catch (RocksDBException | IOException e) {
            if (created) {
                deleteQuietly(target);
            } else {
                emptyQuietly(target);
            }
            throw new BackupFailedException("falha ao restaurar o backup " + id, e);
        }
    }

    /**
     * Restaura o backup {@code id} no lugar da base {@code liveDir}, que não pode estar aberta. Restaura em
     * {@code liveDir/.restoring}, abre o resultado para provar que está legível e só então troca o conteúdo (veja
     * {@link DirectorySwap}). A base anterior fica em {@code .replaced-<instante>} e o caminho dela é devolvido;
     * uma falha em qualquer ponto deixa a base original como estava.
     */
    public static Path restoreInPlace(Path backupDir, int id, Path liveDir) {
        Path restoring = prepareRestore(backupDir, id, liveDir);
        try {
            return DirectorySwap.swap(liveDir, restoring);
        } catch (IOException e) {
            try {
                DirectorySwap.recover(liveDir);
            } catch (IOException recoverFailed) {
                e.addSuppressed(recoverFailed);
            }
            throw new BackupFailedException("falha ao trocar a base restaurada pela original", e);
        }
    }

    /**
     * Restaura o backup {@code id} em {@code liveDir/.restoring} e abre o resultado para provar que está
     * legível. Não toca na base ativa, que pode continuar em uso; a troca vem depois
     * ({@link RocksDbBackend#replaceWith}). Em falha, o diretório temporário é removido.
     */
    public static Path prepareRestore(Path backupDir, int id, Path liveDir) {
        Path restoring = DirectorySwap.restoringDir(liveDir);
        DirectorySwap.deleteTree(restoring);
        try {
            restoreFromDir(backupDir, id, restoring);
            validateOpens(restoring);
        } catch (RuntimeException e) {
            DirectorySwap.deleteTree(restoring);
            throw e;
        }
        return restoring;
    }

    /** Descarta o diretório preparado por {@link #prepareRestore} quando a troca desistiu. */
    public static void discardPrepared(Path liveDir) {
        DirectorySwap.deleteTree(DirectorySwap.restoringDir(liveDir));
    }

    /**
     * Se o processo morreu no meio de uma troca, devolve a base anterior ao lugar. Chame antes de abrir a base;
     * {@link RocksDbBackend} já faz isso ao abrir. Devolve {@code true} quando recuperou algo.
     */
    public static boolean recoverInterruptedSwap(Path liveDir) {
        try {
            return DirectorySwap.recover(liveDir);
        } catch (IOException e) {
            throw new BackupFailedException("falha ao recuperar a troca interrompida de " + liveDir, e);
        }
    }

    // ---- utilidades ----

    private static void validateOpens(Path dir) {
        try (RocksDbBackend b = new RocksDbBackend(dir.toString(), RocksDbBackend.SyncMode.OFF)) {
            b.keysWithPrefix("\u0000");
        } catch (RuntimeException e) {
            throw new BackupFailedException("o banco restaurado não abre: " + dir, e);
        }
    }

    private static long parseVersion(String meta) {
        try {
            return Long.parseLong(field(meta, META_PREFIX));
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static CommitWatermark parseWatermark(String meta) {
        try {
            String[] stamp = field(meta, META_STAMP).split("\\.", 2);
            if (stamp.length < 2) {
                return CommitWatermark.NONE;
            }
            return new CommitWatermark(new HlcTimestamp(Long.parseLong(stamp[0]), Integer.parseInt(stamp[1])),
                Long.parseLong(field(meta, META_INDEX)), Long.parseLong(field(meta, META_TIMELINE)));
        } catch (NumberFormatException ignored) {
            return CommitWatermark.NONE;
        }
    }

    /** O valor do campo {@code name=} nos metadados {@code a=1;b=2}, ou vazio. */
    private static String field(String meta, String name) {
        if (meta != null) {
            for (String part : meta.split(";")) {
                if (part.startsWith(name)) {
                    return part.substring(name.length()).trim();
                }
            }
        }
        return "";
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            s.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // melhor esforço
        }
    }

    private static void emptyQuietly(Path dir) {
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(PhysicalBackups::deleteQuietly);
        } catch (IOException ignored) {
            // melhor esforço
        }
    }
}
