package com.axonbase.core.shard.backup;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.shard.ShardMap;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * O manifesto de um backup do cluster: o corte (o instante comum a todos os shards), o mapa dos shards e, de cada
 * um, o backup físico e o journal.
 *
 * @param cut todo lote com carimbo até o corte está no backup mais o journal; instantes anteriores a ele pedem um
 *            manifesto mais antigo
 */
public record ClusterManifest(String id, HlcTimestamp cut, long createdAtMillis, ShardMap map,
                              List<ShardBackup> shards) {

    private static final int FORMAT = 1;

    /**
     * @param baseIndex    índice do log que o backup certamente contém
     * @param barrierIndex índice da barreira do corte
     */
    public record ShardBackup(String shard, String node, String backupDir, int backupId, long baseIndex,
                              long barrierIndex, String journalDir, String incarnation) {
    }

    public ClusterManifest {
        shards = List.copyOf(shards);
    }

    public ShardBackup shard(String shardId) {
        return shards.stream().filter(s -> s.shard().equals(shardId)).findFirst()
            .orElseThrow(() -> new IllegalArgumentException("shard fora do manifesto: " + shardId));
    }

    public AxonValue toValue() {
        Map<String, AxonValue> out = new LinkedHashMap<>();
        out.put("format", AxonValue.num(FORMAT));
        out.put("id", AxonValue.str(id));
        out.put("cutPhysical", AxonValue.num(cut.physical()));
        out.put("cutLogical", AxonValue.num(cut.logical()));
        out.put("createdAt", AxonValue.num(createdAtMillis));
        out.put("map", map.toValue());
        out.put("shards", AxonValue.array(shards.stream().map(s -> {
            Map<String, AxonValue> entry = new LinkedHashMap<>();
            entry.put("shard", AxonValue.str(s.shard()));
            entry.put("node", AxonValue.str(s.node()));
            entry.put("backupDir", AxonValue.str(s.backupDir()));
            entry.put("backupId", AxonValue.num(s.backupId()));
            entry.put("baseIndex", AxonValue.num(s.baseIndex()));
            entry.put("barrierIndex", AxonValue.num(s.barrierIndex()));
            entry.put("journalDir", AxonValue.str(s.journalDir()));
            entry.put("incarnation", AxonValue.str(s.incarnation()));
            return AxonValue.object(entry);
        }).toList()));
        return AxonValue.object(out);
    }

    public static ClusterManifest fromValue(AxonValue value) {
        Map<String, AxonValue> in = value.asObject();
        long format = in.get("format").asLong();
        if (format != FORMAT) {
            throw new IllegalArgumentException("formato de manifesto desconhecido: " + format);
        }
        List<ShardBackup> shards = new ArrayList<>();
        for (AxonValue item : in.get("shards").asArray()) {
            Map<String, AxonValue> s = item.asObject();
            shards.add(new ShardBackup(s.get("shard").asString(), s.get("node").asString(),
                s.get("backupDir").asString(), (int) s.get("backupId").asLong(), s.get("baseIndex").asLong(),
                s.get("barrierIndex").asLong(), s.get("journalDir").asString(), s.get("incarnation").asString()));
        }
        return new ClusterManifest(in.get("id").asString(),
            new HlcTimestamp(in.get("cutPhysical").asLong(), (int) in.get("cutLogical").asLong()),
            in.get("createdAt").asLong(), ShardMap.fromValue(in.get("map")), shards);
    }

    /** Grava o manifesto de uma vez: um arquivo pela metade nunca fica com o nome pedido. */
    public void write(Path file) {
        try {
            Path parent = file.toAbsolutePath().getParent();
            Files.createDirectories(parent);
            Path partial = Files.createTempFile(parent, "manifest", ".tmp");
            Files.write(partial, AxonJson.write(toValue()).getBytes(StandardCharsets.UTF_8));
            Files.move(partial, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao gravar o manifesto " + file, e);
        }
    }

    public static ClusterManifest read(Path file) {
        try {
            return fromValue(AxonJson.parseDocument(Files.readString(file, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            throw new UncheckedIOException("falha ao ler o manifesto " + file, e);
        }
    }
}
