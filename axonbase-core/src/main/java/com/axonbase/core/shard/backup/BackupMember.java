package com.axonbase.core.shard.backup;

import com.axonbase.core.hlc.HlcTimestamp;

/** Um nó de shard visto pelo coordenador do backup, no mesmo processo ou pela rede. */
public interface BackupMember {

    /** @param clock o relógio híbrido do nó agora */
    record Status(boolean leader, HlcTimestamp clock) {
    }

    /**
     * Uma entrada de barreira confirmada: todo lote confirmado depois dela tem carimbo maior que o pedido.
     *
     * @param index       índice no log do shard
     * @param stamp       carimbo dela
     * @param incarnation identificador da história do shard, que muda a cada restauração
     */
    record Barrier(long index, HlcTimestamp stamp, String incarnation) {
    }

    /**
     * Um backup físico de um nó.
     *
     * @param node      quem copiou: o mesmo nó fecha o journal depois
     * @param baseIndex índice do log que a base certamente contém (ela pode conter mais)
     */
    record Copy(String node, String backupDir, int backupId, long baseIndex, String journalDir, String incarnation) {
    }

    String node();

    Status status();

    /** Só no líder: adianta o relógio até {@code atLeast} e confirma uma entrada de barreira. */
    Barrier barrier(HlcTimestamp atLeast);

    /** Backup físico deste nó, depois de ele aplicar pelo menos o índice pedido. */
    Copy backup(long minIndex);

    /** Fecha o segmento do journal depois de ele receber o índice, e confere que não falta nada desde a base. */
    void seal(long baseIndex, long upToIndex);
}
