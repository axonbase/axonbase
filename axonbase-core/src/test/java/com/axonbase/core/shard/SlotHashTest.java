package com.axonbase.core.shard;

import com.axonbase.value.AxonValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Shards: hash estável da chave de shard")
class SlotHashTest {

    @Test
    void mesmoValorCaiNoMesmoSlotQualquerQueSejaAForma() {
        assertEquals(SlotHash.ofValue(AxonValue.num(1)), SlotHash.ofValue(AxonValue.num(1.0d)));
        assertEquals(SlotHash.ofValue(AxonValue.num(1)), SlotHash.ofValue(AxonValue.num(new BigDecimal("1.00"))));
        // o record id guardado no armazenamento volta como texto
        assertEquals(SlotHash.ofValue(AxonValue.record("cliente", AxonValue.num(5))),
            SlotHash.ofValue(AxonValue.str("cliente:5")));
    }

    @Test
    void chaveDoRecordIdNaoIncluiONomeDaTabela() {
        int literal = SlotHash.ofId("pedido", AxonValue.record("pedido", AxonValue.num(42)));
        int guardado = SlotHash.ofId("pedido", AxonValue.str("pedido:42"));
        int solto = SlotHash.ofValue(AxonValue.num(42));

        assertEquals(solto, literal);
        assertEquals(solto, guardado);
        // tabelas diferentes divididas pelo mesmo valor ficam juntas
        assertEquals(literal, SlotHash.ofId("item", AxonValue.record("item", AxonValue.num(42))));
    }

    @Test
    void slotsFicamNaFaixaEBemDistribuidos() {
        int[] perShard = new int[4];
        for (int i = 0; i < 40_000; i++) {
            int slot = SlotHash.ofValue(AxonValue.str("cliente-" + i));
            assertTrue(slot >= 0 && slot < SlotHash.SLOTS);
            perShard[slot * 4 / SlotHash.SLOTS]++;
        }
        for (int count : perShard) {
            assertTrue(count > 9_000 && count < 11_000, "distribuição desigual: " + count);
        }
    }

    @Test
    void numerosSequenciaisTambemSeEspalham() {
        boolean[] used = new boolean[SlotHash.SLOTS];
        int distinct = 0;
        for (int i = 0; i < 10_000; i++) {
            int slot = SlotHash.ofValue(AxonValue.num(i));
            if (!used[slot]) {
                used[slot] = true;
                distinct++;
            }
        }
        assertTrue(distinct > 3_300, "slots usados: " + distinct);
    }

    @Test
    void datasEBooleanosServemDeChave() {
        Instant now = Instant.parse("2026-11-26T10:00:00Z");
        assertEquals(SlotHash.ofValue(AxonValue.datetime(now)), SlotHash.ofValue(AxonValue.datetime(now)));
        assertEquals(SlotHash.ofValue(AxonValue.bool(true)), SlotHash.ofValue(AxonValue.bool(true)));
    }

    @Test
    void valorAusenteOuCompostoNaoServeDeChave() {
        for (AxonValue invalid : List.of(AxonValue.nul(), AxonValue.none(), AxonValue.array(List.of()),
            AxonValue.object(Map.of()))) {
            ShardException refused = assertThrows(ShardException.class, () -> SlotHash.ofValue(invalid));
            assertEquals(ShardException.Code.REFUSED, refused.code());
        }
        assertThrows(ShardException.class, () -> SlotHash.ofValue(null));
    }
}
