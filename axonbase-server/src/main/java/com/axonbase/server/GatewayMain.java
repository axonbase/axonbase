package com.axonbase.server;

import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.shard.HttpShards;
import com.axonbase.core.shard.ShardClient;
import com.axonbase.core.shard.ShardGateway;
import com.axonbase.core.shard.ShardInfo;
import com.axonbase.core.shard.ShardMap;
import com.axonbase.core.shard.ShardMapStore;
import com.axonbase.core.shard.ShardMover;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Sobe o gateway de shards ({@code axonbase gateway}), configurado pelo ambiente. Veja {@code docs/shards.md}.
 */
public final class GatewayMain {

    private GatewayMain() {
    }

    /**
     * @param meta      endereços dos nós do grupo de metadados (por padrão o shard base)
     * @param bootstrap shards para criar o mapa na primeira subida; vazio não cria
     */
    record Config(List<String> meta, List<ShardInfo> bootstrap, String user, String password, String bind, int port,
                  String adminToken, Duration mapTtl, Duration deadline, boolean checkExplicitIds) {

        static Config from(Map<String, String> env) {
            List<ShardInfo> bootstrap = shards(env.getOrDefault("AXON_GATEWAY_SHARDS", ""));
            List<String> meta = list(env.getOrDefault("AXON_GATEWAY_META", ""));
            if (meta.isEmpty() && !bootstrap.isEmpty()) {
                meta = bootstrap.get(0).endpoints();
            }
            if (meta.isEmpty()) {
                throw new IllegalArgumentException(
                    "defina AXON_GATEWAY_META (nós do grupo de metadados) ou AXON_GATEWAY_SHARDS");
            }
            String explicit = env.getOrDefault("AXON_GATEWAY_EXPLICIT_IDS", "").trim();
            if (!explicit.isEmpty() && !explicit.equals("check") && !explicit.equals("refuse")) {
                throw new IllegalArgumentException("AXON_GATEWAY_EXPLICIT_IDS precisa ser check ou refuse: "
                    + explicit);
            }
            return new Config(meta, bootstrap, env.getOrDefault("AXON_GATEWAY_SHARD_USER", ""),
                env.getOrDefault("AXON_GATEWAY_SHARD_PASS", ""),
                env.getOrDefault("AXON_GATEWAY_BIND", "127.0.0.1"),
                (int) number(env, "AXON_GATEWAY_PORT", 8080), env.getOrDefault("AXON_GATEWAY_ADMIN_TOKEN", ""),
                Duration.ofMillis(number(env, "AXON_GATEWAY_MAP_TTL_MS", 2_000)),
                Duration.ofMillis(number(env, "AXON_GATEWAY_DEADLINE_MS", 15_000)), explicit.equals("check"));
        }

        /** Quanto a movimentação de slots espera: duas validades do mapa mais o tempo de uma requisição. */
        Duration propagation() {
            return mapTtl.multipliedBy(2).plus(deadline);
        }

        /** {@code s0=http://a:8000,http://b:8000;s1=http://c:8000} */
        private static List<ShardInfo> shards(String text) {
            List<ShardInfo> out = new ArrayList<>();
            for (String part : text.split(";")) {
                if (part.isBlank()) {
                    continue;
                }
                int equals = part.indexOf('=');
                if (equals < 1 || list(part.substring(equals + 1)).isEmpty()) {
                    throw new IllegalArgumentException("AXON_GATEWAY_SHARDS inválido perto de: " + part);
                }
                out.add(new ShardInfo(part.substring(0, equals).trim(), list(part.substring(equals + 1))));
            }
            return out;
        }

        private static List<String> list(String text) {
            return Arrays.stream(text.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }

        private static long number(Map<String, String> env, String name, long fallback) {
            String raw = env.get(name);
            if (raw == null || raw.isBlank()) {
                return fallback;
            }
            try {
                long value = Long.parseLong(raw.trim());
                if (value >= 0) {
                    return value;
                }
            } catch (NumberFormatException invalid) {
                // cai no erro abaixo
            }
            throw new IllegalArgumentException(name + " precisa ser um inteiro maior ou igual a zero: " + raw);
        }
    }

    static GatewayServer start(Config config) throws Exception {
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
            .version(HttpClient.Version.HTTP_1_1).build();
        // a credencial do próprio gateway vale só para o plano de controle e a movimentação, nunca para o cliente
        Supplier<String> service = config.user().isEmpty() ? null
            : HttpShards.signin(http, config.meta(), config.user(), config.password(), config.deadline());
        ShardClient meta = HttpShards.client(new ShardInfo("meta", config.meta()), http, config.deadline(), service);
        ShardMapStore store = new ShardMapStore(meta);
        if (!config.bootstrap().isEmpty()) {
            store.bootstrap(ShardMap.initial(config.bootstrap()));
        }
        Function<ShardInfo, ShardClient> users = shard -> HttpShards.client(shard, http, config.deadline(), null);
        Function<ShardInfo, ShardClient> administration =
            shard -> HttpShards.client(shard, http, config.propagation().plus(Duration.ofMinutes(10)), service);
        HybridClock clock = HybridClock.system();
        ShardGateway gateway = new ShardGateway(store, users, ShardGateway.Options.defaults()
            .withMapTtl(config.mapTtl()).withCheckExplicitIds(config.checkExplicitIds()), clock);
        ShardMover mover = new ShardMover(store, administration, config.propagation(), clock);
        return GatewayServer.start(gateway, mover, store,
            new GatewayServer.Settings(config.bind(), config.port(), config.adminToken(), config.meta()));
    }

    static void run(Map<String, String> env) throws Exception {
        Config config = Config.from(env);
        GatewayServer server = start(config);
        Runtime.getRuntime().addShutdownHook(new Thread(server::close, "ax-gateway-shutdown"));
        AxonLog.log("info", "gateway_started", "gateway de shards em " + config.bind() + ":" + server.port());
        Thread.currentThread().join();
    }
}
