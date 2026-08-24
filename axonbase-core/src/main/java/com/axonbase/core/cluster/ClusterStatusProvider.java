package com.axonbase.core.cluster;

/**
 * Fonte de readiness e estado do cluster, usada pelo servidor sem acoplá-lo ao
 * transporte de consenso.
 */
public interface ClusterStatusProvider {

    Status status();

    /**
     * Retrato observável do nó.
     *
     * @param nodeId        identificador deste nó
     * @param clusterId     identificador do cluster
     * @param leader        líder conhecido, vazio durante uma eleição
     * @param leaderAddress endereço {@code host:porta} do líder, vazio se desconhecido
     * @param role          papel deste nó no consenso
     * @param term          termo atual
     * @param commitIndex   último índice aplicado ao state machine
     * @param active        membros vivos conhecidos por este nó
     * @param members       tamanho configurado do grupo
     * @param quorum        maioria necessária para confirmar uma escrita
     */
    record Status(String nodeId, String clusterId, String leader, String leaderAddress,
                  ClusterRole role, long term, long commitIndex, int active, int members,
                  int quorum) {

        public Status {
            leader = leader == null ? "" : leader;
            leaderAddress = leaderAddress == null ? "" : leaderAddress;
            role = role == null ? ClusterRole.FOLLOWER : role;
        }

        /** Este nó é o líder eleito. */
        public boolean isLeader() {
            return role == ClusterRole.LEADER;
        }

        /**
         * Pronto para atender.
         *
         * <p>O líder precisa de maioria viva; um seguidor precisa apenas conhecer um
         * líder, porque ele não tem como contar os demais membros por si só.</p>
         */
        public boolean ready() {
            if (leader.isEmpty()) {
                return false;
            }
            return !isLeader() || active >= quorum;
        }

        /** Estado de um nó único, sem consenso configurado. */
        public static Status local() {
            return new Status("local", "local", "local", "", ClusterRole.LEADER, 0, 0, 1, 1, 1);
        }
    }
}
