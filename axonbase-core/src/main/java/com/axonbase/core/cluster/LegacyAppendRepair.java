package com.axonbase.core.cluster;

import java.util.Arrays;
import java.util.Map;

/**
 * Conserta o termo das entradas recebidas de um líder anterior à fase E2.
 *
 * <p>O protocolo antigo não informa o termo de cada entrada: quem recebe grava com o termo do pedido. Se o líder
 * reenvia uma entrada de um mandato anterior, ela fica aqui com o termo novo, e a entrada seguinte, que informa o
 * termo verdadeiro como anterior, seria recusada para sempre.</p>
 *
 * <p>Os dois métodos rodam sob o monitor do nó e só tocam entradas ainda não aplicadas.</p>
 */
final class LegacyAppendRepair {

    private LegacyAppendRepair() {
    }

    /**
     * O líder do mandato {@code leaderTerm} diz que a entrada {@code index} é do termo {@code trueTerm}. Uma entrada
     * gravada com o termo do próprio líder veio dele (só há um líder por mandato), então é a mesma entrada com o
     * termo errado: regrava com o verdadeiro. O que vinha depois dela é descartado e o líder reenvia.
     *
     * @return {@code false} quando não há prova de que a entrada veio deste líder
     */
    static boolean retag(FileRaftLog log, GroupSync sync, long index, long trueTerm, long leaderTerm) {
        if (index > log.lastIndex() || log.termAt(index) != leaderTerm || trueTerm >= leaderTerm
            || trueTerm < log.termAt(index - 1)) {
            return false;
        }
        FileRaftLog.Entry stored = log.entry(index);
        if (stored == null) {
            return false;
        }
        log.truncateFrom(index);
        sync.truncated(index);
        log.append(new FileRaftLog.Entry(index, trueTerm, stored.batch()));
        // A entrada já tinha sido confirmada ao líder como durável: volta ao disco antes de seguir.
        log.sync();
        return true;
    }

    /**
     * A entrada {@code index} já está aqui com o mesmo conteúdo e um termo anterior ao do líder: é um reenvio de uma
     * entrada já corrigida, e truncar apagaria as seguintes.
     */
    static boolean alreadyStored(FileRaftLog log, long index, CommittedBatch incoming, long leaderTerm) {
        if (log.termAt(index) >= leaderTerm) {
            return false;
        }
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
