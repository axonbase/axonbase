package com.axonbase.core.shard;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** Os clientes dos shards pela rede, montados a partir do que o mapa diz deles. */
public final class HttpShards {

    /** Um token é renovado depois deste tempo, bem antes de os servidores o recusarem. */
    private static final long TOKEN_NANOS = Duration.ofMinutes(10).toNanos();

    private HttpShards() {
    }

    public static ShardClient client(ShardInfo shard, HttpClient http, Duration deadline,
                                     Supplier<String> credential) {
        return client(shard, http, deadline, credential, null);
    }

    /** @param secret segredo do gateway que os nós exigem, ou {@code null} */
    public static ShardClient client(ShardInfo shard, HttpClient http, Duration deadline,
                                     Supplier<String> credential, String secret) {
        if (shard.endpoints().isEmpty()) {
            throw ShardException.refused("shard_map_invalid", shard.id() + " sem endereço");
        }
        List<ShardMember> members = shard.endpoints().stream()
            .map(endpoint -> (ShardMember) new HttpShardMember(shard.id(), endpoint, http, deadline, credential,
                secret))
            .toList();
        return new GroupShardClient(shard.id(), () -> members, deadline);
    }

    /**
     * Credencial do próprio gateway (plano de controle, movimentação e backup): entra com usuário e senha em algum dos
     * nós e reaproveita o token por um tempo.
     *
     * @return o cabeçalho {@code Authorization}
     */
    public static Supplier<String> signin(HttpClient http, List<String> endpoints, String user, String password,
                                          Duration timeout) {
        Map<String, AxonValue> body = new LinkedHashMap<>();
        body.put("user", AxonValue.str(user));
        body.put("pass", AxonValue.str(password));
        String payload = AxonJson.write(AxonValue.object(body));
        return new Supplier<>() {
            private String header;
            private long takenAt;

            @Override
            public synchronized String get() {
                if (header != null && System.nanoTime() - takenAt < TOKEN_NANOS) {
                    return header;
                }
                RuntimeException last = null;
                for (String endpoint : endpoints) {
                    try {
                        HttpResponse<String> response = http.send(HttpRequest.newBuilder(
                                URI.create(endpoint.replaceAll("/+$", "") + "/signin")).timeout(timeout)
                            .POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                            HttpResponse.BodyHandlers.ofString());
                        if (response.statusCode() == 200) {
                            header = "Bearer " + AxonJson.parseDocument(response.body()).asObject().get("token")
                                .asString();
                            takenAt = System.nanoTime();
                            return header;
                        }
                        last = new ShardException(ShardException.Code.UNAVAILABLE,
                            ShardMessages.get("shard_signin", endpoint, response.statusCode()));
                    } catch (IOException e) {
                        last = new ShardException(ShardException.Code.UNAVAILABLE,
                            ShardMessages.get("shard_signin", endpoint, e.getMessage()), e);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new ShardException(ShardException.Code.UNAVAILABLE,
                            ShardMessages.get("shard_signin", endpoint, "interrompido"), e);
                    }
                }
                throw last != null ? last
                    : new ShardException(ShardException.Code.UNAVAILABLE, ShardMessages.get("shard_signin", "-", "-"));
            }
        };
    }
}
