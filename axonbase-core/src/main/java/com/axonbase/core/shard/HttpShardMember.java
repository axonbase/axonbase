package com.axonbase.core.shard;

import com.axonbase.core.cluster.CommitOutcomeUnknownException;
import com.axonbase.core.engine.Datastore;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.function.Supplier;

/**
 * Um nó de shard do outro lado da rede. Só diz que nada foi executado quando é certo (a conexão não abriu, ou a
 * requisição só lia); uma conexão perdida no meio de uma escrita é um desfecho desconhecido.
 */
public final class HttpShardMember implements ShardMember {

    private final String shard;
    private final URI execute;
    private final HttpClient http;
    private final Duration timeout;
    private final Supplier<String> credential;
    private final String secret;

    /**
     * @param endpoint   endereço base do nó, como {@code http://10.0.0.1:8000}
     * @param credential cabeçalho {@code Authorization} usado quando a requisição não traz um (operações do próprio
     *                   gateway); {@code null} nunca acrescenta credencial
     */
    public HttpShardMember(String shard, String endpoint, HttpClient http, Duration timeout,
                           Supplier<String> credential) {
        this(shard, endpoint, http, timeout, credential, null);
    }

    /** @param secret segredo do gateway que o nó exige, ou {@code null} */
    public HttpShardMember(String shard, String endpoint, HttpClient http, Duration timeout,
                           Supplier<String> credential, String secret) {
        this.secret = secret;
        this.shard = shard;
        this.execute = URI.create(endpoint.replaceAll("/+$", "") + ShardHttp.EXECUTE);
        this.http = http;
        this.timeout = timeout;
        this.credential = credential;
    }

    @Override
    public ShardReply handle(ShardRequest request) {
        HttpRequest.Builder message = HttpRequest.newBuilder(execute)
            .timeout(timeout)
            .header("Content-Type", ShardHttp.CONTENT_TYPE)
            .POST(HttpRequest.BodyPublishers.ofByteArray(ShardCodec.encode(request)));
        if (secret != null) {
            message.header(ShardHttp.SECRET_HEADER, secret);
        }
        // o nó confere a credencial contra estes cabeçalhos, e recusa um corpo que peça outro banco
        if (request.namespace() != null) {
            message.header("Axon-Ns", request.namespace());
        }
        if (request.database() != null) {
            message.header("Axon-Db", request.database());
        }
        String authorization = request.authorization() != null ? request.authorization()
            : credential == null ? null : credential.get();
        if (authorization != null) {
            message.header("Authorization", authorization);
        }
        HttpResponse<byte[]> response;
        try {
            response = http.send(message.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (ConnectException | HttpConnectTimeoutException notSent) {
            throw new ShardUnreachableException(execute + ": " + notSent.getMessage(), notSent);
        } catch (IOException broken) {
            throw interrupted(request, broken);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw interrupted(request, e);
        }
        if (response.statusCode() == 200) {
            return ShardCodec.decodeReply(response.body());
        }
        throw ShardHttp.error(shard, response.statusCode(), new String(response.body(), StandardCharsets.UTF_8));
    }

    private RuntimeException interrupted(ShardRequest request, Exception cause) {
        boolean wrote = request.atomic() || !request.partial() && Datastore.isMutation(request.sql());
        return wrote ? new CommitOutcomeUnknownException(shard, cause)
            : new ShardUnreachableException(execute + ": " + cause.getMessage(), cause);
    }
}
