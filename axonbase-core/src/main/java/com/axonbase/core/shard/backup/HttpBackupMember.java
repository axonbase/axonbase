package com.axonbase.core.shard.backup;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.core.shard.ShardHttp;
import com.axonbase.core.shard.ShardUnreachableException;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Um nó de shard visto pelo coordenador do backup através da rede. Toda operação daqui pode ser repetida sem dano,
 * então uma falha de conexão só faz o coordenador tentar de novo.
 */
public final class HttpBackupMember implements BackupMember {

    private static final Duration QUICK = Duration.ofSeconds(30);

    private final String shard;
    private final String base;
    private final HttpClient http;
    private final Duration longOperation;
    private final Supplier<String> credential;
    private final String secret;
    private volatile String node;

    /**
     * @param longOperation prazo de um backup físico
     * @param credential    cabeçalho {@code Authorization} de administrador, ou {@code null} sem autenticação
     */
    public HttpBackupMember(String shard, String endpoint, HttpClient http, Duration longOperation,
                            Supplier<String> credential) {
        this(shard, endpoint, http, longOperation, credential, null);
    }

    /** @param secret segredo do gateway que o nó exige, ou {@code null} */
    public HttpBackupMember(String shard, String endpoint, HttpClient http, Duration longOperation,
                            Supplier<String> credential, String secret) {
        this.secret = secret;
        this.shard = shard;
        this.base = endpoint.replaceAll("/+$", "") + BackupHttp.PREFIX;
        this.http = http;
        this.longOperation = longOperation;
        this.credential = credential;
    }

    @Override
    public String node() {
        if (node == null) {
            status();
        }
        return node;
    }

    @Override
    public Status status() {
        Map<String, AxonValue> out = call("status", Map.of(), QUICK);
        node = out.get("node").asString();
        return new Status(out.get("leader").asBool(), BackupHttp.stamp(out));
    }

    @Override
    public Barrier barrier(HlcTimestamp atLeast) {
        Map<String, AxonValue> in = new LinkedHashMap<>();
        BackupHttp.stamp(in, atLeast);
        Map<String, AxonValue> out = call("barrier", in, QUICK);
        return new Barrier(out.get("index").asLong(), BackupHttp.stamp(out), out.get("incarnation").asString());
    }

    @Override
    public Copy backup(long minIndex) {
        Map<String, AxonValue> out = call("physical", Map.of("minIndex", AxonValue.num(minIndex)), longOperation);
        return new Copy(out.get("node").asString(), out.get("backupDir").asString(),
            (int) out.get("backupId").asLong(), out.get("baseIndex").asLong(), out.get("journalDir").asString(),
            out.get("incarnation").asString());
    }

    @Override
    public void seal(long baseIndex, long upToIndex) {
        Map<String, AxonValue> in = new LinkedHashMap<>();
        in.put("baseIndex", AxonValue.num(baseIndex));
        in.put("upToIndex", AxonValue.num(upToIndex));
        call("seal", in, longOperation);
    }

    private Map<String, AxonValue> call(String action, Map<String, AxonValue> body, Duration timeout) {
        URI uri = URI.create(base + action);
        HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(AxonJson.write(AxonValue.object(body))));
        if (credential != null) {
            request.header("Authorization", credential.get());
        }
        if (secret != null) {
            request.header(ShardHttp.SECRET_HEADER, secret);
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw ShardHttp.error(shard, response.statusCode(), response.body());
            }
            return AxonJson.parseDocument(response.body()).asObject();
        } catch (IOException e) {
            throw new ShardUnreachableException(uri + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShardUnreachableException(uri + ": interrompido", e);
        }
    }
}
