package com.axonbase.core.cluster;

/**
 * A escrita chegou a um nó que não é o líder atual.
 *
 * <p>Carrega o identificador e o endereço do líder conhecido para que o
 * transporte possa redirecionar o cliente em vez de apenas recusar. Os campos
 * ficam vazios quando o nó ainda não sabe quem lidera, o que acontece durante
 * uma eleição.</p>
 */
public final class NotLeaderException extends RuntimeException {

    private final String leader;
    private final String leaderAddress;

    public NotLeaderException(String leader) {
        this(leader, "");
    }

    public NotLeaderException(String leader, String leaderAddress) {
        super(message(leader, leaderAddress));
        this.leader = leader == null ? "" : leader;
        this.leaderAddress = leaderAddress == null ? "" : leaderAddress;
    }

    /** Identificador do líder conhecido, ou vazio durante uma eleição. */
    public String leader() {
        return leader;
    }

    /** Endereço {@code host:porta} do líder, ou vazio se desconhecido. */
    public String leaderAddress() {
        return leaderAddress;
    }

    /** O cliente tem para onde ser redirecionado. */
    public boolean hasLeaderAddress() {
        return !leaderAddress.isEmpty();
    }

    private static String message(String leader, String address) {
        if (leader == null || leader.isBlank()) {
            return "escrita recusada: nenhum líder conhecido neste momento";
        }
        return "escrita deve ser enviada ao líder " + leader
            + (address == null || address.isBlank() ? "" : " em " + address);
    }
}
