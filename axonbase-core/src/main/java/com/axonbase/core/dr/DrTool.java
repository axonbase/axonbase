package com.axonbase.core.dr;

import com.axonbase.core.backup.S3BackupClient;
import com.axonbase.core.cluster.ClusterRestore;
import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.partition.ColdTier;

import java.io.PrintStream;
import java.nio.file.Path;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

/**
 * Linha de comando da recuperação de desastre, DR (Disaster Recovery).
 *
 * <pre>
 * DrTool push    --store s3|&lt;dir&gt; --epoch e1 --node n1 [--backups &lt;dir&gt;] [--journal &lt;dir&gt;]
 * DrTool watch   ... os mesmos de push ... [--every 10]
 * DrTool restore --store s3|&lt;dir&gt; --epoch e1 --target &lt;dir&gt; --work &lt;dir&gt; [--at &lt;instante ISO&gt;|latest]
 * </pre>
 *
 * <p>Comuns: {@code --prefix axon-dr} e {@code --group g}. Com {@code --store s3}, o endereço e as credenciais vêm de
 * {@code AXON_S3_ENDPOINT}, {@code AXON_S3_BUCKET}, {@code AXON_S3_ACCESS} e {@code AXON_S3_SECRET}; elas nunca são
 * impressas. {@code AXON_S3_ENCRYPT_KEY}, se presente, cifra e decifra os objetos. Uma base com partições na camada
 * fria só é restaurada com {@code AXON_COLD_DIR} ou {@code AXON_COLD_S3} apontando para os objetos dela, que o
 * arquivamento não copia. Cada linha de saída começa com
 * {@code DR}; o código de saída é 1 numa falha e 2 num uso errado.</p>
 */
public final class DrTool {

    private static final int USAGE = 2;
    private static final int TRANSFERS = 8;

    private DrTool() {
    }

    public static void main(String[] args) {
        System.exit(run(args, System.getenv(), System.out));
    }

    static int run(String[] args, Map<String, String> env, PrintStream out) {
        Map<String, String> options = new HashMap<>();
        for (int i = 1; i + 1 < args.length; i += 2) {
            options.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        }
        String command = args.length == 0 ? "" : args[0];
        try {
            return switch (command) {
                case "push" -> push(options, env, out, false);
                case "watch" -> push(options, env, out, true);
                case "restore" -> restore(options, env, out);
                default -> usage(out, "comando desconhecido: " + command);
            };
        } catch (IllegalArgumentException wrong) {
            return usage(out, wrong.getMessage());
        } catch (ClusterRestore.Unreachable unreachable) {
            out.println("DR FAILED " + unreachable.getMessage() + " latest="
                + Instant.ofEpochMilli(unreachable.latest().physical()));
            return 1;
        } catch (RuntimeException failure) {
            out.println("DR FAILED " + failure.getMessage());
            return 1;
        }
    }

    private static int usage(PrintStream out, String problem) {
        out.println("DR USAGE " + problem);
        out.println("DR USAGE DrTool push|watch|restore --store s3|<dir> --epoch <época> ... (veja a classe DrTool)");
        return USAGE;
    }

    private static String required(Map<String, String> options, String name) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("falta --" + name);
        }
        return value;
    }

    private static ArchiveStore store(Map<String, String> options, Map<String, String> env) {
        String where = required(options, "store");
        ArchiveStore store = where.equals("s3")
            ? new S3ArchiveStore(new S3BackupClient(fromEnv(env, "AXON_S3_ENDPOINT"), "",
                fromEnv(env, "AXON_S3_ACCESS"), fromEnv(env, "AXON_S3_SECRET")), fromEnv(env, "AXON_S3_BUCKET"))
            : new DirectoryArchiveStore(Path.of(where));
        String key = env.get("AXON_S3_ENCRYPT_KEY");
        return key == null || key.isBlank() ? store : new EncryptedArchiveStore(store, key);
    }

    private static String fromEnv(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("falta a variável " + name);
        }
        return value;
    }

    private static ArchiveLayout layout(Map<String, String> options) {
        return new ArchiveLayout(options.getOrDefault("prefix", "axon-dr"), required(options, "epoch"),
            options.getOrDefault("group", "g"));
    }

    private static int push(Map<String, String> options, Map<String, String> env, PrintStream out, boolean watch) {
        DrArchiver archiver = new DrArchiver(store(options, env), layout(options), required(options, "node"));
        String backups = options.get("backups");
        String journal = options.get("journal");
        if (backups == null && journal == null) {
            throw new IllegalArgumentException("informe --backups, --journal ou os dois");
        }
        long pause = Math.max(1, Long.parseLong(options.getOrDefault("every", "10"))) * 1_000L;
        while (true) {
            // o journal vai primeiro e cada parte falha sozinha: um backup com defeito não segura os segmentos
            List<String> errors = new ArrayList<>(2);
            int sentSegments = send(journal, archiver::pushSegments, errors);
            int sentBackups = send(backups, archiver::pushBackups, errors);
            out.println("DR push node=" + options.get("node") + " backups=" + sentBackups + " segments="
                + sentSegments + (errors.isEmpty() ? "" : " error=" + String.join(" | ", errors)));
            if (!watch) {
                return errors.isEmpty() ? 0 : 1;
            }
            try {
                Thread.sleep(pause);
            } catch (InterruptedException stopped) {
                Thread.currentThread().interrupt();
                return 0;
            }
        }
    }

    /** O armazenamento pode estar fora por um tempo: o erro é anotado e a próxima volta envia o que faltou. */
    private static int send(String directory, ToIntFunction<Path> push, List<String> errors) {
        if (directory == null) {
            return 0;
        }
        try {
            return push.applyAsInt(Path.of(directory));
        } catch (RuntimeException failure) {
            errors.add(String.valueOf(failure.getMessage()));
            return 0;
        }
    }

    private static int restore(Map<String, String> options, Map<String, String> env, PrintStream out) {
        String at = options.getOrDefault("at", "latest");
        HlcTimestamp until = null;
        if (!at.equalsIgnoreCase("latest")) {
            try {
                until = HlcTimestamp.endOfMillisecond(Instant.parse(at).toEpochMilli());
            } catch (DateTimeParseException invalid) {
                throw new IllegalArgumentException("instante inválido (use 2026-01-31T14:32:10Z): " + at);
            }
        }
        long started = System.nanoTime();
        DrRestore.Result result;
        try (ColdTier cold = ColdTier.fromEnvironment(env)) {
            result = DrRestore.restore(store(options, env), layout(options),
                Path.of(required(options, "work")), Path.of(required(options, "target")), until,
                Integer.parseInt(options.getOrDefault("transfers", Integer.toString(TRANSFERS))), cold);
        }
        ClusterRestore.Prepared prepared = result.prepared();
        out.println("DR restore backup=" + result.backupId() + " node=" + result.node() + " base_index="
            + prepared.baseIndex() + " last_index=" + prepared.lastIndex() + " batches=" + prepared.appliedBatches()
            + " segments=" + result.segments() + " bytes=" + result.bytes() + " download_ms="
            + result.downloadMillis() + " total_ms=" + (System.nanoTime() - started) / 1_000_000L);
        out.println("DR restore target=" + prepared.directory() + " restored_until="
            + (prepared.lastStamp().isNone() ? "backup" : Instant.ofEpochMilli(prepared.lastStamp().physical())));
        return 0;
    }
}
