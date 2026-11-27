package com.axonbase.core.cluster;

import com.axonbase.core.hlc.ClockDriftException;
import com.axonbase.core.hlc.HybridClock;

import java.util.Objects;

/**
 * Liga o relógio híbrido (HLC) à replicação: o líder carimba cada lote ao propor, quem recebe adota o carimbo (ou
 * recusa o lote que chega além do desvio máximo) e, no arranque, o relógio parte da cauda do log. Assim a ordem dos
 * índices do log é a ordem dos carimbos, inclusive depois de uma troca de líder.
 *
 * <p>Use o relógio do armazenamento ({@code RocksDbBackend#clock}), que já garante em disco um teto acima de tudo que
 * emite. Com o PITR ligado em cluster, o journal envolve estes ganchos
 * ({@code com.axonbase.core.shard.backup.ShardJournalHooks}).</p>
 */
public final class ClockHooks implements ReplicationHooks {

    private final HybridClock clock;

    public ClockHooks(HybridClock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** Os carimbos crescem com o índice, então a última entrada do log é a maior. */
    @Override
    public void recovered(CommittedBatch batch) {
        clock.observe(batch.stamp());
    }

    /**
     * Carimba sempre, mesmo um lote que já traga carimbo: vale a ordem em que o log o recebe. Cobre também o lote de
     * um snapshot, porque quem o recebe pula as entradas que ele substitui e só conhece este carimbo.
     */
    @Override
    public CommittedBatch stamp(CommittedBatch batch) {
        return batch.withStamp(clock.now());
    }

    @Override
    public boolean accept(CommittedBatch batch) {
        try {
            clock.receive(batch.stamp());
            return true;
        } catch (ClockDriftException drift) {
            // o relógio já registrou o alerta; o líder reenvia, e o lote passa quando o relógio local alcançar
            return false;
        }
    }
}
