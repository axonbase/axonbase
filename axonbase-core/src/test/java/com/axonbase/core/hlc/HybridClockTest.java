package com.axonbase.core.hlc;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentSkipListSet;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HybridClockTest {

    private final AtomicLong wall = new AtomicLong(1_000_000L);
    private final List<HybridClock.DriftEvent> alerts = new ArrayList<>();
    private final HybridClock clock = new HybridClock(wall::get, 500L, alerts::add);

    @Test
    void emiteOFisicoDoRelogioEIncrementaOContadorQuandoORelogioNaoAvanca() {
        assertEquals(new HlcTimestamp(1_000_000L, 0), clock.now());
        assertEquals(new HlcTimestamp(1_000_000L, 1), clock.now());
        wall.set(1_000_005L);
        assertEquals(new HlcTimestamp(1_000_005L, 0), clock.now());
    }

    @Test
    void naoRecuaQuandoORelogioRecua() {
        HlcTimestamp first = clock.now();
        wall.set(10L);
        HlcTimestamp second = clock.now();
        assertEquals(new HlcTimestamp(first.physical(), first.logical() + 1), second);
    }

    @Test
    void contadorCheioAvancaOFisico() {
        clock.observe(new HlcTimestamp(2_000_000L, Integer.MAX_VALUE));
        assertEquals(new HlcTimestamp(2_000_001L, 0), clock.now());
    }

    /** Portão 1: relógio falso que salta para trás e para frente, 1 milhão de commits. */
    @Test
    void umMilhaoDeCarimbosCrescemEstritamenteComRelogioSaltando() {
        Random random = new Random(42);
        HlcTimestamp previous = clock.now();
        for (int i = 0; i < 1_000_000; i++) {
            int dice = random.nextInt(100);
            if (dice < 5) {
                wall.addAndGet(-random.nextInt(100_000));
            } else if (dice < 10) {
                wall.addAndGet(random.nextInt(100_000));
            } else if (dice < 40) {
                wall.incrementAndGet();
            }
            HlcTimestamp next = clock.now();
            assertTrue(next.compareTo(previous) > 0, "carimbo " + i + " recuou: " + previous + " -> " + next);
            previous = next;
        }
    }

    @Test
    void emissaoConcorrenteNuncaRepeteCarimbo() throws Exception {
        var seen = new ConcurrentSkipListSet<HlcTimestamp>();
        List<Thread> threads = new ArrayList<>();
        for (int t = 0; t < 8; t++) {
            threads.add(new Thread(() -> {
                for (int i = 0; i < 5_000; i++) {
                    assertTrue(seen.add(clock.now()));
                }
            }));
        }
        threads.forEach(Thread::start);
        for (Thread thread : threads) {
            thread.join();
        }
        assertEquals(40_000, seen.size());
    }

    @Test
    void receberUmCarimboMaiorPuxaORelogioLocal() {
        clock.receive(new HlcTimestamp(1_000_300L, 7));
        assertEquals(new HlcTimestamp(1_000_300L, 8), clock.now());
    }

    @Test
    void receberUmCarimboMenorNaoMudaNada() {
        HlcTimestamp mine = clock.now();
        clock.receive(new HlcTimestamp(5L, 9));
        assertEquals(mine, clock.last());
    }

    @Test
    void loteSemCarimboEAceitoSemMudarORelogio() {
        HlcTimestamp mine = clock.now();
        clock.receive(HlcTimestamp.NONE);
        assertEquals(mine, clock.last());
    }

    /** Portão 4: desvio acima do limite rejeita o lote e emite alerta. */
    @Test
    void rejeitaCarimboAlemDoDesvioMaximoEAlerta() {
        clock.receive(new HlcTimestamp(1_000_500L, 0));
        ClockDriftException error = assertThrows(ClockDriftException.class,
            () -> clock.receive(new HlcTimestamp(1_000_501L, 0)));
        assertEquals(501L, error.driftMs());
        assertEquals(new HlcTimestamp(1_000_500L, 0), clock.last(), "o lote rejeitado não pode puxar o relógio");
        assertEquals(1, alerts.size());
        assertEquals(501L, alerts.get(0).driftMs());
        assertEquals(1L, clock.rejectedCount());
        assertEquals(501L, clock.maxObservedDriftMs());
    }

    /** Portão 3: no reinício o primeiro carimbo novo é maior que o último persistido. */
    @Test
    void reinicioComecaAcimaDoPersistidoMesmoComRelogioAtrasado() {
        HlcTimestamp persisted = new HlcTimestamp(9_000_000L, 55);
        clock.restore(persisted);
        HlcTimestamp first = clock.now();
        assertTrue(first.compareTo(persisted) > 0);
        assertEquals(9_000_001L, first.physical());
    }

    @Test
    void reinicioComRelogioAdiantadoSegueORelogio() {
        clock.restore(new HlcTimestamp(10L, 3));
        assertEquals(new HlcTimestamp(1_000_000L, 0), clock.now());
    }

    @Test
    void reiniciarSemNadaPersistidoNaoAdiantaORelogio() {
        clock.restore(HlcTimestamp.NONE);
        assertEquals(new HlcTimestamp(1_000_000L, 0), clock.now());
    }

    /** Portão 2: troca de líder com desvio de 300 ms para mais e para menos. */
    @Test
    void trocaDeLiderComDesvioMantemOrdemDeIndiceIgualAOrdemDeCarimbo() {
        long[] skews = {0L, 300L, -300L};
        List<HybridClock> nodes = new ArrayList<>();
        for (long skew : skews) {
            nodes.add(new HybridClock(() -> wall.get() + skew, 700L, alerts::add));
        }
        Random random = new Random(7);
        List<HlcTimestamp> log = new ArrayList<>();
        int leader = 0;
        for (int i = 0; i < 30_000; i++) {
            if (random.nextInt(500) == 0) {
                leader = random.nextInt(nodes.size());
            }
            if (random.nextInt(4) == 0) {
                wall.incrementAndGet();
            }
            HlcTimestamp stamp = nodes.get(leader).now();
            log.add(stamp);
            for (HybridClock follower : nodes) {
                follower.receive(stamp);
            }
        }
        for (int i = 1; i < log.size(); i++) {
            assertTrue(log.get(i).compareTo(log.get(i - 1)) > 0, "índice " + i + " fora da ordem de carimbo");
        }
        assertTrue(alerts.isEmpty());
    }

    /** Dois nós em lados opostos (600 ms entre si) passam do padrão de 500 ms: rejeição temporária. */
    @Test
    void nosSeparadosPorMaisQueOLimiteRejeitamAteORelogioAlcancar() {
        HybridClock ahead = new HybridClock(() -> wall.get() + 300, 500L, alerts::add);
        HybridClock behind = new HybridClock(() -> wall.get() - 300, 500L, alerts::add);
        HlcTimestamp stamp = ahead.now();

        assertThrows(ClockDriftException.class, () -> behind.receive(stamp));
        wall.addAndGet(100);
        behind.receive(stamp);

        assertTrue(behind.now().compareTo(stamp) > 0);
    }

    /** Teto em memória que anota cada pedido; {@code fail} imita um disco que não grava. */
    private static final class FakeCeiling implements HybridClock.Ceiling {
        long durable;
        boolean fail;
        final List<Long> raised = new ArrayList<>();

        @Override
        public long durableMillis() {
            return durable;
        }

        @Override
        public void raise(long atLeastMillis) {
            if (fail) {
                throw new IllegalStateException("disco cheio");
            }
            raised.add(atLeastMillis);
            durable = atLeastMillis + 100;
        }
    }

    @Test
    void sobeOTetoAntesDeEmitirUmCarimboAcimaDeleESoNessaHora() {
        FakeCeiling ceiling = new FakeCeiling();
        clock.guard(ceiling);

        clock.now();
        wall.addAndGet(100);
        clock.now();
        assertEquals(List.of(1_000_000L), ceiling.raised, "dentro do teto nada é gravado");

        wall.addAndGet(1);
        HlcTimestamp above = clock.now();
        assertEquals(List.of(1_000_000L, 1_000_101L), ceiling.raised);
        assertTrue(ceiling.durable >= above.physical());
    }

    @Test
    void semConseguirGravarOTetoNenhumCarimboSai() {
        FakeCeiling ceiling = new FakeCeiling();
        clock.guard(ceiling);
        HlcTimestamp before = clock.now();
        ceiling.fail = true;
        wall.addAndGet(1_000);

        assertThrows(IllegalStateException.class, clock::now);
        assertEquals(before, clock.last(), "o carimbo recusado não pode ficar valendo");
    }

    @Test
    void carimboObservadoAcimaDoTetoSoSaiDepoisDeOTetoSubir() {
        FakeCeiling ceiling = new FakeCeiling();
        clock.guard(ceiling);
        clock.now();
        clock.observe(new HlcTimestamp(5_000_000L, 3));

        assertEquals(new HlcTimestamp(5_000_000L, 4), clock.now());
        assertEquals(5_000_000L, ceiling.raised.get(ceiling.raised.size() - 1));
    }

    @Test
    void emRepousoEntregaOUltimoFisicoSemDeixarEmitirNoMeio() {
        clock.now();
        wall.addAndGet(7);
        clock.now();
        long[] seen = new long[1];

        clock.settle(physical -> seen[0] = physical);

        assertEquals(1_000_007L, seen[0]);
    }

    @Test
    void comparaFisicoAntesDoContadorEOInstanteIncluiTodosOsContadores() {
        assertTrue(new HlcTimestamp(2, 0).compareTo(new HlcTimestamp(1, 99)) > 0);
        assertTrue(new HlcTimestamp(1, 2).compareTo(new HlcTimestamp(1, 1)) > 0);
        assertEquals(new HlcTimestamp(5, Integer.MAX_VALUE), HlcTimestamp.endOfMillisecond(5));
        assertEquals(new HlcTimestamp(5, 0), new HlcTimestamp(5, 1).predecessor());
        assertEquals(new HlcTimestamp(4, Integer.MAX_VALUE), new HlcTimestamp(5, 0).predecessor());
        assertTrue(HlcTimestamp.NONE.isNone());
    }
}
