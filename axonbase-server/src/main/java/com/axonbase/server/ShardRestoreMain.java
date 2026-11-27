package com.axonbase.server;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.partition.ColdTier;
import com.axonbase.core.shard.backup.ClusterManifest;
import com.axonbase.core.shard.backup.ClusterRestore;

import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Restaura um backup de cluster com shards, fora de linha:
 *
 * <pre>
 *   axonbase restore-shards &lt;manifesto.json&gt; &lt;destino&gt; [--at &lt;instante&gt;] [--source &lt;shard&gt;=&lt;backup&gt;,&lt;journal&gt;]
 * </pre>
 *
 * <p>Cria {@code <destino>/<shard>} para cada shard. Copie cada um para o diretório de dados de todos os membros do
 * shard, com o diretório do Raft vazio. Sem {@code --source}, valem os caminhos anotados no manifesto.</p>
 *
 * <p>Se alguma base tiver partições na camada fria, defina {@code AXON_COLD_DIR} ou {@code AXON_COLD_S3} como nos
 * nós: sem isso, ou faltando objeto, nada é restaurado.</p>
 */
public final class ShardRestoreMain {

    private static final String USAGE = "uso: axonbase restore-shards <manifesto.json> <destino> "
        + "[--at <instante ISO 8601>] [--source <shard>=<diretório do backup>,<diretório do journal>]...";

    private ShardRestoreMain() {
    }

    static void run(String[] args) {
        if (args.length < 2) {
            throw new IllegalArgumentException(USAGE);
        }
        ClusterManifest manifest = ClusterManifest.read(Path.of(args[0]));
        Path destination = Path.of(args[1]);
        Map<String, ClusterRestore.Source> sources = new LinkedHashMap<>();
        manifest.shards().forEach(s -> sources.put(s.shard(),
            new ClusterRestore.Source(Path.of(s.backupDir()), Path.of(s.journalDir()))));
        HlcTimestamp target = null;
        for (int i = 2; i < args.length; i += 2) {
            if (i + 1 >= args.length) {
                throw new IllegalArgumentException(USAGE);
            }
            if ("--at".equals(args[i])) {
                target = instant(args[i + 1]);
            } else if ("--source".equals(args[i])) {
                String text = args[i + 1];
                int equals = text.indexOf('=');
                int comma = text.indexOf(',', equals + 1);
                if (equals < 1 || comma < 0 || !sources.containsKey(text.substring(0, equals))) {
                    throw new IllegalArgumentException("origem inválida: " + text + "\n" + USAGE);
                }
                sources.put(text.substring(0, equals), new ClusterRestore.Source(
                    Path.of(text.substring(equals + 1, comma)), Path.of(text.substring(comma + 1))));
            } else {
                throw new IllegalArgumentException("opção desconhecida: " + args[i] + "\n" + USAGE);
            }
        }
        try (ColdTier cold = ColdTier.fromEnvironment(System.getenv())) {
            ClusterRestore.restore(manifest, target, sources, destination, cold).forEach((shard, restored) ->
                System.out.println(shard + ": índice base " + restored.baseIndex() + ", " + restored.appliedBatches()
                    + " lotes reaplicados, até " + restored.restoredUntil() + " em " + destination.resolve(shard)));
        }
    }

    /** Um instante inclui todos os carimbos do milissegundo pedido. */
    static HlcTimestamp instant(String text) {
        try {
            Instant instant = text.endsWith("Z") ? Instant.parse(text) : OffsetDateTime.parse(text).toInstant();
            return HlcTimestamp.endOfMillisecond(instant.toEpochMilli());
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("instante inválido: " + text, invalid);
        }
    }
}
