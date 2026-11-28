package com.axonbase.core.shard.backup;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.value.AxonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * As rotas de backup de um nó de shard e o formato delas, em JSON (JavaScript Object Notation), num lugar só para o
 * servidor e o cliente nunca discordarem.
 */
public final class BackupHttp {

    /** Prefixo das rotas; todas são {@code POST} e exigem o papel admin. */
    public static final String PREFIX = "/shard/v1/backup/";

    private BackupHttp() {
    }

    /** Atende uma rota no nó. Devolve {@code null} quando a rota não existe. */
    public static AxonValue serve(BackupMember node, String action, AxonValue request) {
        Map<String, AxonValue> in = request.isObject() ? request.asObject() : Map.of();
        Map<String, AxonValue> out = new LinkedHashMap<>();
        switch (action) {
            case "status" -> {
                BackupMember.Status status = node.status();
                out.put("node", AxonValue.str(node.node()));
                out.put("leader", AxonValue.bool(status.leader()));
                stamp(out, status.clock());
            }
            case "barrier" -> {
                BackupMember.Barrier barrier = node.barrier(stamp(in));
                out.put("index", AxonValue.num(barrier.index()));
                out.put("incarnation", AxonValue.str(barrier.incarnation()));
                stamp(out, barrier.stamp());
            }
            case "physical" -> {
                BackupMember.Copy copy = node.backup(in.get("minIndex").asLong());
                out.put("node", AxonValue.str(copy.node()));
                out.put("backupDir", AxonValue.str(copy.backupDir()));
                out.put("backupId", AxonValue.num(copy.backupId()));
                out.put("baseIndex", AxonValue.num(copy.baseIndex()));
                out.put("journalDir", AxonValue.str(copy.journalDir()));
                out.put("incarnation", AxonValue.str(copy.incarnation()));
            }
            case "seal" -> node.seal(in.get("baseIndex").asLong(), in.get("upToIndex").asLong());
            default -> {
                return null;
            }
        }
        return AxonValue.object(out);
    }

    static void stamp(Map<String, AxonValue> out, HlcTimestamp stamp) {
        out.put("physical", AxonValue.num(stamp.physical()));
        out.put("logical", AxonValue.num(stamp.logical()));
    }

    static HlcTimestamp stamp(Map<String, AxonValue> in) {
        return new HlcTimestamp(in.get("physical").asLong(), (int) in.get("logical").asLong());
    }
}
