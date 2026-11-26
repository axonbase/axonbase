package com.axonbase.core.cluster;

/**
 * O log Raft em disco tem bytes corrompidos onde havia dados confirmados. O nó não abre: seguir adiante
 * poderia perder uma escrita já confirmada. A saída é restaurar o diretório do nó a partir de um backup
 * físico (ou apagar o diretório do Raft para o líder reenviar o estado).
 */
public final class CorruptRaftLogException extends RuntimeException {

    public CorruptRaftLogException(String message) {
        super(message);
    }
}
