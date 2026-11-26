package com.axonbase.core.shard;

import com.axonbase.core.hlc.ClockDriftException;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.value.AxonValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Move slots de um shard para outro, em passos gravados no plano de controle: anunciar, esperar, copiar e conferir,
 * trocar o dono, esperar, apagar da origem e encerrar. Uma queda em qualquer ponto se resolve com {@link #resume()}.
 *
 * <p>A segurança depende de duas condições: todo acesso passa por um gateway, e a espera ({@code propagation}) é maior
 * que a validade do mapa nos gateways somada ao tempo máximo de uma requisição. Rode um movimentador por vez.</p>
 */
public final class ShardMover {

    private static final int BATCH = 500;
    private static final String ROWS = "__axon_rows";

    private final ShardMapStore store;
    private final Function<ShardInfo, ShardClient> clients;
    private final Duration propagation;
    private final HybridClock clock;

    /**
     * @param clients     clientes com a credencial de administrador do próprio gateway
     * @param propagation quanto esperar para ter certeza de que nenhum gateway age pelo mapa anterior
     */
    public ShardMover(ShardMapStore store, Function<ShardInfo, ShardClient> clients, Duration propagation,
                      HybridClock clock) {
        this.store = store;
        this.clients = clients;
        this.propagation = propagation;
        this.clock = clock;
    }

    /** Copia as definições do shard base para um shard vazio e o acrescenta ao mapa, ainda sem slots. */
    public ShardMap addShard(ShardInfo shard) {
        ShardMap map = store.require();
        GatewayRules.requireNoMigration(map);
        if (map.shardIds().contains(shard.id())) {
            throw ShardException.refused("shard_map_invalid", "shard repetido: " + shard.id());
        }
        ShardClient target = clients.apply(shard);
        AxonValue definitions = send(client(map, map.home().id()), ShardRequest.definitions());
        for (AxonValue item : definitions.asArray()) {
            Map<String, AxonValue> command = item.asObject();
            String namespace = emptyToNull(command.get("ns").asString());
            String database = emptyToNull(command.get("db").asString());
            String definition = command.get("definition").asString();
            // um banco sem definição própria é criado por qualquer consulta sobre ele
            send(target, ShardRequest.of(namespace, database,
                definition.isEmpty() ? "INFO FOR DATABASE" : definition));
        }
        return store.update(current -> current.withShard(shard));
    }

    /** Move as faixas (de um mesmo dono) para o destino. Repetir a mesma chamada retoma de onde parou. */
    public ShardMap move(List<SlotRange> ranges, String target) {
        ShardMap map = store.require();
        if (map.migration().isEmpty()) {
            ShardMap next = map.startMigration(ranges, target);
            if (!store.compareAndSet(map.epoch(), next)) {
                throw new ShardException(ShardException.Code.STALE_MAP,
                    ShardMessages.get("shard_map_invalid", "o mapa mudou; repita"));
            }
        } else {
            ShardMap.Migration running = map.migration().get();
            if (!running.target().equals(target) || !running.ranges().equals(ranges)) {
                GatewayRules.requireNoMigration(map);
            }
        }
        return resume();
    }

    /** Leva a movimentação em curso até o fim. */
    public ShardMap resume() {
        ShardMap map = store.require();
        ShardMap.Migration migration = migration(map);
        if (migration.phase() == ShardMap.MigrationPhase.COPY) {
            settle();
            for (Map.Entry<ShardMap.TableKey, String> table : map.tables().entrySet()) {
                copy(map, migration, table.getKey(), table.getValue());
            }
            map = store.update(current -> current.migration().orElseThrow().phase() == ShardMap.MigrationPhase.COPY
                ? current.switchOwner() : current);
        }
        settle();
        for (Map.Entry<ShardMap.TableKey, String> table : map.tables().entrySet()) {
            delete(map, migration.source(), table.getKey(), table.getValue(), migration.ranges());
        }
        return store.update(ShardMap::finishMigration);
    }

    /** Desiste antes da troca de dono: apaga a cópia do destino. */
    public ShardMap abort() {
        ShardMap map = store.require();
        ShardMap.Migration migration = migration(map);
        if (migration.phase() != ShardMap.MigrationPhase.COPY) {
            throw ShardException.refused("shard_map_invalid", "o dono já mudou: retome a movimentação");
        }
        settle();
        // a cópia sai antes de o filtro das leituras sair
        for (Map.Entry<ShardMap.TableKey, String> table : map.tables().entrySet()) {
            delete(map, migration.target(), table.getKey(), table.getValue(), migration.ranges());
        }
        return store.update(ShardMap::abortMigration);
    }

    private void copy(ShardMap map, ShardMap.Migration migration, ShardMap.TableKey table, String field) {
        // sobras de uma tentativa anterior saem antes, para os índices do destino não contarem duas vezes
        delete(map, migration.target(), table, field, migration.ranges());
        SlotFilter filter = new SlotFilter(table.table(), field, migration.ranges());
        ShardClient target = client(map, migration.target());
        List<AxonValue> source = read(client(map, migration.source()), table, filter);
        List<AxonValue> batch = new ArrayList<>(BATCH);
        for (AxonValue item : source) {
            batch.add(insertable(item));
            if (batch.size() == BATCH) {
                insert(target, table, batch);
                batch = new ArrayList<>(BATCH);
            }
        }
        if (!batch.isEmpty()) {
            insert(target, table, batch);
        }
        verify(table, source, read(target, table, filter));
    }

    /** Os registros da tabela nas faixas, cada um com a chave exata (modo parcial). */
    private List<AxonValue> read(ShardClient shard, ShardMap.TableKey table, SlotFilter filter) {
        ShardRequest request = ShardRequest.of(table.namespace(), table.database(), "SELECT * FROM " + table.table())
            .partial(filter);
        return send(shard, request).asObject().get("items").asArray();
    }

    /** O registro com o id trocado pela chave crua, que é o que um INSERT espera. */
    private static AxonValue insertable(AxonValue item) {
        String key = item.asObject().get("k").asString();
        Map<String, AxonValue> row = new LinkedHashMap<>(item.asObject().get("r").asObject());
        if (key.startsWith("n")) {
            row.put("id", AxonValue.num(new BigDecimal(key.substring(1)).longValue()));
        } else if (key.startsWith("s")) {
            row.put("id", AxonValue.str(key.substring(1)));
        } else {
            throw ShardException.refused("record_key_unsupported", key);
        }
        return AxonValue.object(row);
    }

    private void insert(ShardClient target, ShardMap.TableKey table, List<AxonValue> rows) {
        send(target, ShardRequest.of(table.namespace(), table.database(),
                "INSERT INTO " + table.table() + " $" + ROWS + " RETURN NONE")
            .withVars(Map.of(ROWS, AxonValue.array(rows))));
    }

    /** Só troca o dono quando o destino tem exatamente os mesmos registros, com o mesmo conteúdo. */
    private static void verify(ShardMap.TableKey table, List<AxonValue> source, List<AxonValue> target) {
        // a comparação ignora a ordem dos campos: o motor regrava o id no fim do registro
        Map<String, AxonValue> expected = contents(source);
        Map<String, AxonValue> found = contents(target);
        if (!expected.equals(found)) {
            throw new ShardException(ShardException.Code.PARTIAL_WRITE,
                ShardMessages.get("move_verification", table.table(), expected.size(), found.size()));
        }
    }

    private static Map<String, AxonValue> contents(List<AxonValue> items) {
        Map<String, AxonValue> out = new java.util.TreeMap<>();
        for (AxonValue item : items) {
            out.put(item.asObject().get("k").asString(), item.asObject().get("r"));
        }
        return out;
    }

    private void delete(ShardMap map, String shard, ShardMap.TableKey table, String field, List<SlotRange> ranges) {
        send(client(map, shard), ShardRequest.of(table.namespace(), table.database(),
            "DELETE " + table.table() + " WHERE " + new SlotFilter(table.table(), field, ranges).condition()));
    }

    private ShardClient client(ShardMap map, String shardId) {
        return clients.apply(map.shards().stream().filter(s -> s.id().equals(shardId)).findFirst()
            .orElseThrow(() -> ShardException.refused("shard_unknown", shardId)));
    }

    private AxonValue send(ShardClient shard, ShardRequest request) {
        ShardReply reply = shard.execute(request.withStamp(clock.now()));
        try {
            clock.receive(reply.stamp());
        } catch (ClockDriftException drift) {
            // o alerta já saiu pelo relógio
        }
        return reply.value();
    }

    private static ShardMap.Migration migration(ShardMap map) {
        return map.migration()
            .orElseThrow(() -> ShardException.refused("shard_map_invalid", "não há movimentação em curso"));
    }

    private void settle() {
        try {
            Thread.sleep(propagation.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ShardException(ShardException.Code.UNAVAILABLE,
                ShardMessages.get("shard_unavailable", "*", "interrompido"), e);
        }
    }

    private static String emptyToNull(String text) {
        return text.isEmpty() ? null : text;
    }
}
