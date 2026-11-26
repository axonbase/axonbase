package com.axonbase.server;

import java.util.HashMap;
import java.util.Map;

/**
 * Para onde mandar o cliente HTTP quando a escrita chegou a um nó que não lidera. Sem
 * {@code AXON_CLUSTER_HTTP_PEERS}, vale o comportamento anterior: o endereço que o líder anuncia ao consenso.
 */
final class LeaderRedirect {

    static final LeaderRedirect NONE = new LeaderRedirect(Map.of());

    private final Map<String, String> httpByNode;

    private LeaderRedirect(Map<String, String> httpByNode) {
        this.httpByNode = httpByNode;
    }

    /**
     * @param raw pares {@code nó=url} separados por vírgula, por exemplo {@code n1=http://10.0.0.1:8000}
     * @throws IllegalArgumentException para um par que não se entende
     */
    static LeaderRedirect parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return NONE;
        }
        Map<String, String> out = new HashMap<>();
        for (String pair : raw.split(",")) {
            if (pair.isBlank()) {
                continue;
            }
            int equals = pair.indexOf('=');
            String url = equals < 1 ? "" : pair.substring(equals + 1).trim();
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                throw new IllegalArgumentException("AXON_CLUSTER_HTTP_PEERS espera nó=url: " + pair.trim());
            }
            while (url.endsWith("/")) {
                url = url.substring(0, url.length() - 1);
            }
            out.put(pair.substring(0, equals).trim(), url);
        }
        return new LeaderRedirect(Map.copyOf(out));
    }

    /**
     * @return a base da URL do líder, ou vazio quando não se sabe para onde mandar
     */
    String base(String leader, String raftAddress) {
        String configured = httpByNode.get(leader);
        if (configured != null) {
            return configured;
        }
        return raftAddress == null || raftAddress.isEmpty() ? "" : "http://" + raftAddress;
    }
}
