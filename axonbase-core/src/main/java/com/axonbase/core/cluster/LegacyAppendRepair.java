package com.axonbase.core.cluster;

import java.util.Arrays;
import java.util.Map;
import java.util.TreeSet;

/**
 * Acerta o termo das entradas recebidas de um líder anterior à fase E2.
 *
 * <p>O protocolo antigo não informa o termo de cada entrada: quem recebe grava com o termo do pedido. Assim dois nós
 * podem guardar o mesmo lote com termos diferentes. Quando esse líder envia a entrada seguinte e informa o termo
 * dele como anterior, a conferência falharia para sempre: o líder volta uma posição, reenvia o mesmo lote e avança
 * de novo.</p>
 *
 * <p>Depois que o líder do mandato atual entrega uma entrada, está provado que o conteúdo é o dele, e o termo que
 * ele informar para ela pode ser adotado. Só roda sob o monitor do nó e só toca entradas ainda não aplicadas.</p>
 */
final class LegacyAppendRepair {

    /** Entregas recentes lembradas: o líder antigo anda de uma em uma, então poucas bastam. */
    private static final int REMEMBERED = 64;

    private long leaderTerm;
    private final TreeSet<Long> delivered = new TreeSet<>();

    /** O líder do mandato {@code term} entregou a entrada {@code index}, e o log daqui a tem com o mesmo conteúdo. */
    void delivered(long index, long term) {
        if (term != leaderTerm) {
            leaderTerm = term;
            delivered.clear();
        }
        delivered.add(index);
        while (delivered.size() > REMEMBERED) {
            delivered.pollFirst();
        }
    }

    /** As entradas a partir de {@code index} vão ser trocadas: as entregas delas não provam mais nada. */
    void forgetFrom(long index) {
        delivered.tailSet(index).clear();
    }

    /**
     * O líder do mandato {@code term} diz que a entrada {@code index} é do termo {@code claimed}. Se o conteúdo dela
     * veio comprovadamente dele (entrega lembrada, ou entrada gravada com o próprio mandato, que sobrevive a um
     * reinício), regrava com esse termo. O que vinha depois é descartado e o líder reenvia.
     *
     * @return {@code false} quando não há prova de que o conteúdo é o do líder
     */
    boolean retag(FileRaftLog log, GroupSync sync, long index, long claimed, long term) {
        if (index > log.lastIndex() || claimed <= 0 || claimed > term) {
            return false;
        }
        boolean fromThisLeader = log.termAt(index) == term || leaderTerm == term && delivered.contains(index);
        FileRaftLog.Entry stored = fromThisLeader ? log.entry(index) : null;
        if (stored == null) {
            return false;
        }
        forgetFrom(index + 1);
        log.truncateFrom(index);
        sync.truncated(index);
        log.append(new FileRaftLog.Entry(index, claimed, stored.batch()));
        // A entrada já tinha sido confirmada ao líder como durável: volta ao disco antes de seguir.
        log.sync();
        return true;
    }

    /** A entrada {@code index} já está aqui com o mesmo conteúdo: é um reenvio, e truncar apagaria as seguintes. */
    boolean alreadyStored(FileRaftLog log, long index, CommittedBatch incoming) {
        FileRaftLog.Entry stored = log.entry(index);
        return stored != null && sameContent(stored.batch(), incoming);
    }

    /** Ignora o carimbo: um nó antigo no meio do caminho não o repassa. */
    private static boolean sameContent(CommittedBatch left, CommittedBatch right) {
        if (!left.transactionId().equals(right.transactionId()) || left.fullSnapshot() != right.fullSnapshot()
            || !left.deletes().equals(right.deletes()) || !left.puts().keySet().equals(right.puts().keySet())) {
            return false;
        }
        for (Map.Entry<String, byte[]> put : left.puts().entrySet()) {
            if (!Arrays.equals(put.getValue(), right.puts().get(put.getKey()))) {
                return false;
            }
        }
        return true;
    }
}
