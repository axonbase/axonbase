package com.axonbase.core.shard;

import com.axonbase.core.hlc.ClockDriftException;
import com.axonbase.core.hlc.HybridClock;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Move slots de um shard para outro, em passos gravados no plano de controle: anunciar, esperar, copiar e conferir,
 * trocar o dono, esperar, apagar da origem e encerrar. Uma queda em qualquer ponto se resolve com {@link #resume()}.
 *
 * <p>A segurança depende de duas condições: todo acesso passa por um gateway, e a espera ({@code propagation}) é maior
 * que a validade do mapa nos gateways somada ao tempo máximo de uma requisição. Uma execução que assume a movimentação
 * tira a anterior, que para antes do passo seguinte.</p>
 */
public final class ShardMover {

    private static final int BATCH = 500;
    private static final String ROWS = "__axon_rows";
    private static final String SAGA_NAMESPACE = "system";
    private static final String SAGA_DATABASE = "saga";
    private static final String SAGA_TABLE = "saga";

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
        this.store = store.stampedBy(clock);
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
        replicate(client(map, map.home().id()), target);
        ShardMap added = store.update(current -> current.withShard(shard));
        // uma definição feita por um gateway que ainda não via o shard novo chega nesta segunda passada
        settle();
        replicate(client(map, map.home().id()), target);
        return added;
    }

    /** Aplica no destino as definições do shard base que ele ainda não tem. */
    private void replicate(ShardClient home, ShardClient target) {
        List<AxonValue> wanted = send(home, ShardRequest.definitions()).asArray();
        Set<String> present = new HashSet<>();
        send(target, ShardRequest.definitions()).asArray().forEach(item -> present.add(AxonJson.write(item)));
        for (AxonValue item : wanted) {
            if (present.contains(AxonJson.write(item))) {
                continue;
            }
            Map<String, AxonValue> command = item.asObject();
            String namespace = emptyToNull(command.get("ns").asString());
            String database = emptyToNull(command.get("db").asString());
            String definition = command.get("definition").asString();
            // um banco sem definição própria é criado por qualquer consulta sobre ele
            send(target, ShardRequest.of(namespace, database,
                definition.isEmpty() ? "INFO FOR DATABASE" : definition));
        }
    }

    /** Move as faixas (de um mesmo dono) para o destino. Repetir a mesma chamada retoma de onde parou. */
    public ShardMap move(List<SlotRange> ranges, String target) {
        ShardMap map = store.require();
        if (map.migration().isEmpty()) {
            ShardMap next = map.startMigration(ranges, target);
            requireNoOpenSaga(map, next.migration().orElseThrow().source());
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

    /** Leva a movimentação em curso até o fim, tomando o lugar de quem a conduzia. */
    public ShardMap resume() {
        String runner = UUID.randomUUID().toString();
        ShardMap map = claim(runner, null);
        ShardMap.Migration migration = migration(map);
        if (migration.phase() == ShardMap.MigrationPhase.COPY) {
            settle();
            requireSagasClosed(map, runner);
            for (Map.Entry<ShardMap.TableKey, String> table : map.tables().entrySet()) {
                copy(map, runner, table.getKey(), table.getValue());
            }
            map = store.update(current -> owned(current, runner, ShardMap.MigrationPhase.COPY).switchOwner());
        }
        settle();
        for (Map.Entry<ShardMap.TableKey, String> table : map.tables().entrySet()) {
            owned(store.require(), runner, ShardMap.MigrationPhase.CLEANUP);
            delete(map, migration.source(), table.getKey(), table.getValue(), migration.ranges());
        }
        return store.update(current -> owned(current, runner, ShardMap.MigrationPhase.CLEANUP).finishMigration());
    }

    /** Desiste antes da troca de dono: apaga a cópia do destino. */
    public ShardMap abort() {
        String runner = UUID.randomUUID().toString();
        ShardMap map = claim(runner, ShardMap.MigrationPhase.COPY);
        // quem conduzia antes pode ter um comando a caminho: ele chega durante a espera
        settle();
        return discard(map, runner);
    }

    /** Apaga a cópia do destino e encerra a movimentação. */
    private ShardMap discard(ShardMap map, String runner) {
        ShardMap.Migration migration = migration(map);
        // a cópia sai antes de o filtro das leituras sair
        for (Map.Entry<ShardMap.TableKey, String> table : map.tables().entrySet()) {
            owned(store.require(), runner, ShardMap.MigrationPhase.COPY);
            delete(map, migration.target(), table.getKey(), table.getValue(), migration.ranges());
        }
        return store.update(current -> owned(current, runner, ShardMap.MigrationPhase.COPY).abortMigration());
    }

    private ShardMap claim(String runner, ShardMap.MigrationPhase required) {
        return store.update(current -> {
            ShardMap.Migration migration = migration(current);
            if (required != null && migration.phase() != required) {
                throw ShardException.refused("shard_map_invalid", "o dono já mudou: retome a movimentação");
            }
            return current.withRunner(runner);
        });
    }

    /** O mapa, se a movimentação ainda é desta execução e está na fase esperada. */
    private static ShardMap owned(ShardMap map, String runner, ShardMap.MigrationPhase phase) {
        boolean mine = map.migration()
            .filter(m -> m.runner().equals(runner) && m.phase() == phase).isPresent();
        if (!mine) {
            throw new ShardException(ShardException.Code.STALE_MAP, ShardMessages.get("move_superseded"));
        }
        return map;
    }

    /** Uma saga aberta antes do anúncio pode ter passos que a compensação desfaria no dono antigo. */
    private void requireSagasClosed(ShardMap map, String runner) {
        try {
            requireNoOpenSaga(map, migration(map).source());
        } catch (ShardException open) {
            if (open.code() == ShardException.Code.REFUSED) {
                discard(map, runner);
            }
            throw open;
        }
    }

    private void requireNoOpenSaga(ShardMap map, String shard) {
        ShardClient source = client(map, shard);
        AxonValue ledger = send(source, ShardRequest.of(SAGA_NAMESPACE, SAGA_DATABASE, "INFO FOR DATABASE"));
        // consultar uma tabela que não existe a criaria
        if (!GatewayRules.mentions(ledger, SAGA_TABLE)) {
            return;
        }
        AxonValue open = send(source, ShardRequest.of(SAGA_NAMESPACE, SAGA_DATABASE,
            "SELECT * FROM " + SAGA_TABLE + " WHERE status = 'RUNNING' LIMIT 1"));
        if (open.isArray() && !open.asArray().isEmpty()) {
            throw ShardException.refused("move_saga_open", shard);
        }
    }

    private void copy(ShardMap map, String runner, ShardMap.TableKey table, String field) {
        ShardMap.Migration migration = migration(map);
        // sobras de uma tentativa anterior saem antes, para os índices do destino não contarem duas vezes
        owned(store.require(), runner, ShardMap.MigrationPhase.COPY);
        delete(map, migration.target(), table, field, migration.ranges());
        SlotFilter filter = new SlotFilter(table.table(), field, migration.ranges());
        ShardClient target = client(map, migration.target());
        List<AxonValue> source = read(client(map, migration.source()), table, filter);
        for (int from = 0; from < source.size(); from += BATCH) {
            owned(store.require(), runner, ShardMap.MigrationPhase.COPY);
            insert(target, table, source.subList(from, Math.min(from + BATCH, source.size()))
                .stream().map(ShardMover::insertable).toList());
        }
        // sem o lugar, o destino já pode ter sido esvaziado por quem o assumiu
        owned(store.require(), runner, ShardMap.MigrationPhase.COPY);
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

    /** Tudo o que o movimentador envia é manutenção: sem permissões de tabela nem Data Rules. */
    private AxonValue send(ShardClient shard, ShardRequest request) {
        ShardReply reply = shard.execute(request.asSystem().withStamp(clock.now()));
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
