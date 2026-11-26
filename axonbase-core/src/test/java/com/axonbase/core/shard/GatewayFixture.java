package com.axonbase.core.shard;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.core.storage.MemoryBackend;
import com.axonbase.value.AxonValue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Um conjunto de shards de nó único em memória com um gateway na frente. */
final class GatewayFixture implements AutoCloseable {

    final List<Datastore> shards = new ArrayList<>();
    final Map<String, ShardClient> clients = new LinkedHashMap<>();
    final ShardMapStore store;
    final ShardGateway gateway;
    private final GatewaySession session = new GatewaySession("n", "d");

    private GatewayFixture(int count, ShardGateway.Options options) {
        List<ShardInfo> infos = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String id = "s" + i;
            infos.add(new ShardInfo(id, List.of()));
            add(id);
        }
        store = new ShardMapStore(clients.get("s0"));
        store.bootstrap(ShardMap.initial(infos));
        gateway = new ShardGateway(store, info -> clients.get(info.id()), options, HybridClock.system());
    }

    static GatewayFixture memory(int count) {
        // validade zero: cada instrução lê o mapa, e as mudanças feitas direto no plano de controle valem na hora
        return new GatewayFixture(count, ShardGateway.Options.defaults().withMapTtl(Duration.ZERO));
    }

    static GatewayFixture memory(int count, ShardGateway.Options options) {
        return new GatewayFixture(count, options);
    }

    /** Sobe mais um shard, ainda fora do mapa. */
    Datastore add(String id) {
        Datastore datastore = new Datastore(new MemoryBackend());
        ShardNode node = new ShardNode(datastore, null, HybridClock.system());
        shards.add(datastore);
        clients.put(id, new GroupShardClient(id, () -> List.<ShardMember>of(node), Duration.ofSeconds(5)));
        return datastore;
    }

    GatewaySession session() {
        return session;
    }

    AxonValue sql(String script) {
        return gateway.execute(session, script, null);
    }

    AxonValue sql(String script, Map<String, AxonValue> vars) {
        return gateway.execute(session, script, vars);
    }

    /** Executa direto num shard, sem passar pelo gateway. */
    AxonValue onShard(int shard, String script) {
        Session direct = Session.create();
        direct.namespace("n");
        direct.database("d");
        return shards.get(shard).execute(script, direct, null);
    }

    int count(int shard, String table) {
        return onShard(shard, "SELECT * FROM " + table).asArray().size();
    }

    /** O número do shard dono do valor de chave. */
    int ownerOf(AxonValue key) {
        return Integer.parseInt(store.require().ownerOf(SlotHash.ofValue(key)).substring(1));
    }

    /** Um texto cuja chave mora no shard pedido. */
    String keyOn(int shard) {
        for (int i = 0; ; i++) {
            if (ownerOf(AxonValue.str("c" + i)) == shard) {
                return "c" + i;
            }
        }
    }

    @Override
    public void close() {
        gateway.close();
    }
}
