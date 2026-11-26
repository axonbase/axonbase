package com.axonbase.core.shard;

import com.axonbase.value.AxonJson;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: mapa dos shards")
class ShardTopologyTest {

    private static final ShardMap.TableKey ORDERS = new ShardMap.TableKey("n", "d", "pedido");

    private static ShardMap three() {
        return ShardMap.initial(List.of(new ShardInfo("s0", List.of("http://a")),
            new ShardInfo("s1", List.of("http://b")), new ShardInfo("s2", List.of("http://c"))));
    }

    @Test
    void mapaInicialDivideTodosOsSlotsEmFaixasIguais() {
        ShardMap map = three();

        assertEquals(1, map.epoch());
        assertEquals("s0", map.home().id());
        int total = 0;
        for (ShardInfo shard : map.shards()) {
            int owned = map.slotsOf(shard.id()).stream().mapToInt(SlotRange::size).sum();
            assertTrue(owned >= SlotHash.SLOTS / 3 && owned <= SlotHash.SLOTS / 3 + 1, shard.id() + ": " + owned);
            total += owned;
        }
        assertEquals(SlotHash.SLOTS, total);
        assertEquals("s0", map.ownerOf(0));
        assertEquals("s2", map.ownerOf(SlotHash.SLOTS - 1));
    }

    @Test
    void tabelaDivididaEntraESaiDoMapaSubindoAEpoca() {
        ShardMap map = three().withTable(ORDERS, "cliente");

        assertEquals(2, map.epoch());
        assertEquals("cliente", map.shardKey(ORDERS).orElseThrow());
        assertTrue(map.hasShardedTables("n", "d"));
        assertFalse(map.hasShardedTables("n", "outro"));

        ShardMap without = map.withoutTable(ORDERS);
        assertEquals(3, without.epoch());
        assertTrue(without.shardKey(ORDERS).isEmpty());
    }

    @Test
    void movimentacaoMudaODonoSoNaTroca() {
        SlotRange range = new SlotRange(0, 99);
        ShardMap moving = three().startMigration(List.of(range), "s1");

        assertEquals("s0", moving.ownerOf(50));
        assertTrue(moving.moving(50));
        assertFalse(moving.moving(100));
        assertEquals(ShardMap.MigrationPhase.COPY, moving.migration().orElseThrow().phase());
        assertEquals("s0", moving.migration().orElseThrow().source());

        ShardMap switched = moving.switchOwner();
        assertEquals("s1", switched.ownerOf(50));
        assertEquals(ShardMap.MigrationPhase.CLEANUP, switched.migration().orElseThrow().phase());
        assertTrue(switched.moving(50));

        ShardMap done = switched.finishMigration();
        assertFalse(done.moving(50));
        assertTrue(done.migration().isEmpty());
        assertEquals(moving.epoch() + 2, done.epoch());
    }

    @Test
    void desistirAntesDaTrocaMantemODono() {
        ShardMap aborted = three().startMigration(List.of(new SlotRange(0, 9)), "s2").abortMigration();

        assertEquals("s0", aborted.ownerOf(0));
        assertTrue(aborted.migration().isEmpty());
    }

    @Test
    void movimentacaoInvalidaERecusada() {
        ShardMap map = three();
        // faixa com dois donos, destino igual à origem, destino desconhecido, segunda movimentação, troca sem cópia
        assertThrows(ShardException.class, () -> map.startMigration(List.of(new SlotRange(0, 4095)), "s1"));
        assertThrows(ShardException.class, () -> map.startMigration(List.of(new SlotRange(0, 9)), "s0"));
        assertThrows(ShardException.class, () -> map.startMigration(List.of(new SlotRange(0, 9)), "s9"));
        assertThrows(ShardException.class, () -> map.startMigration(List.of(new SlotRange(0, 9)), "s1")
            .startMigration(List.of(new SlotRange(10, 19)), "s1"));
        assertThrows(ShardException.class, map::switchOwner);
        assertThrows(ShardException.class,
            () -> map.startMigration(List.of(new SlotRange(0, 9)), "s1").switchOwner().abortMigration());
        assertThrows(ShardException.class,
            () -> map.startMigration(List.of(new SlotRange(0, 9)), "s1").finishMigration());
    }

    @Test
    void shardNovoEntraSemSlots() {
        ShardMap map = three().withShard(new ShardInfo("s3", List.of("http://d")));

        assertTrue(map.slotsOf("s3").isEmpty());
        assertEquals(4, map.shards().size());
        assertThrows(ShardException.class, () -> map.withShard(new ShardInfo("s3", List.of("http://e"))));
    }

    @Test
    void codificacaoPreservaOMapaInteiro() {
        ShardMap map = three().withTable(ORDERS, "cliente").startMigration(List.of(new SlotRange(10, 20)), "s2");

        ShardMap read = ShardMap.fromValue(AxonJson.parseDocument(AxonJson.write(map.toValue())));

        assertEquals(map, read);
        assertEquals(map.epoch(), read.epoch());
        assertEquals("s0", read.ownerOf(15));
        assertEquals("cliente", read.shardKey(ORDERS).orElseThrow());
    }

    @Test
    void faixasDeSlotsIdaEVoltaComoTexto() {
        List<SlotRange> ranges = List.of(new SlotRange(0, 10), new SlotRange(20, 20));

        assertEquals("0-10,20-20", SlotRange.format(ranges));
        assertEquals(ranges, SlotRange.parse("0-10,20-20"));
        assertEquals(List.of(), SlotRange.parse(""));
        assertThrows(IllegalArgumentException.class, () -> new SlotRange(5, 4));
        assertThrows(IllegalArgumentException.class, () -> new SlotRange(0, SlotHash.SLOTS));
    }
}
