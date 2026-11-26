package com.axonbase.core.shard;

import com.axonbase.common.AxonError;
import com.axonbase.core.cluster.CommitOutcomeUnknownException;
import com.axonbase.core.cluster.NotLeaderException;
import com.axonbase.core.cluster.QuorumUnavailableException;
import com.axonbase.core.cluster.StaleReadException;
import com.axonbase.core.hlc.ClockDriftException;
import com.axonbase.core.storage.VersionConflictException;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A rota interna entre gateway e shards: o caminho e a tradução dos erros nos dois sentidos, num lugar só para o
 * servidor e o cliente nunca discordarem.
 */
public final class ShardHttp {

    /** Rota de execução num nó de shard. */
    public static final String EXECUTE = "/shard/v1/execute";
    /** Tipo de conteúdo do corpo binário. */
    public static final String CONTENT_TYPE = "application/vnd.axonbase.shard";

    private ShardHttp() {
    }

    /** Resposta de erro: o status HTTP (HyperText Transfer Protocol) e o corpo. */
    public record Failure(int status, String body) {
    }

    public static Failure failure(RuntimeException error) {
        if (error instanceof NotLeaderException notLeader) {
            return failure(421, "NOT_LEADER", notLeader.getMessage(), notLeader.leaderAddress());
        }
        if (error instanceof CommitOutcomeUnknownException) {
            return failure(504, "OUTCOME_UNKNOWN", error.getMessage(), null);
        }
        if (error instanceof QuorumUnavailableException) {
            return failure(503, "NO_QUORUM", error.getMessage(), null);
        }
        if (error instanceof StaleReadException) {
            return failure(503, "STALE_READ", error.getMessage(), null);
        }
        if (error instanceof VersionConflictException) {
            return failure(409, "CONFLICT", error.getMessage(), null);
        }
        if (error instanceof ClockDriftException) {
            return failure(409, "CLOCK_DRIFT", error.getMessage(), null);
        }
        if (error instanceof ShardException shard) {
            return failure(409, "SHARD_" + shard.code().name(), error.getMessage(), null);
        }
        int code = error instanceof AxonError axon ? axon.code() : -32000;
        return failure(code == -32002 ? 403 : 400, "ERR", error.getMessage(), String.valueOf(code));
    }

    private static Failure failure(int status, String code, String message, String detail) {
        Map<String, AxonValue> body = new LinkedHashMap<>();
        body.put("code", AxonValue.str(code));
        body.put("message", AxonValue.str(message == null ? "" : message));
        body.put("detail", AxonValue.str(detail == null ? "" : detail));
        return new Failure(status, AxonJson.write(AxonValue.object(body)));
    }

    /** A exceção que uma resposta de erro representa do lado do gateway. */
    public static RuntimeException error(String shard, int status, String body) {
        String code = "";
        String message = body;
        String detail = "";
        try {
            Map<String, AxonValue> parsed = AxonJson.parseDocument(body).asObject();
            code = parsed.get("code").asString();
            message = parsed.get("message").asString();
            detail = parsed.get("detail").asString();
        } catch (RuntimeException notOurs) {
            // um intermediário respondeu outra coisa: vale o status
        }
        return switch (code) {
            case "NOT_LEADER" -> new NotLeaderException("", detail);
            case "OUTCOME_UNKNOWN" -> new CommitOutcomeUnknownException(shard);
            case "NO_QUORUM" -> new QuorumUnavailableException(shard);
            case "STALE_READ" -> new StaleReadException(message);
            case "CONFLICT" -> new VersionConflictException(message, "");
            case "ERR" -> new AxonError(detail.isEmpty() ? -32000 : Integer.parseInt(detail), message);
            default -> {
                if (code.startsWith("SHARD_")) {
                    yield new ShardException(ShardException.Code.valueOf(code.substring("SHARD_".length())), message);
                }
                yield new AxonError(status == 401 || status == 403 ? -32002 : -32000,
                    "shard " + shard + " respondeu " + status + ": " + message);
            }
        };
    }
}
