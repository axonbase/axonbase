package com.axonbase.server;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Portões da E5")
class ResilienceGatesTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("o cliente só repete o que certamente não aconteceu")
    void clientContract() {
        assertEquals(GateClient.Verdict.DONE, GateClient.classify(200, "[]"));
        assertEquals(GateClient.Verdict.RETRY, GateClient.classify(307, ""));
        assertEquals(GateClient.Verdict.RETRY, GateClient.classify(503, "{\"kind\":\"NOT_LEADER\"}"));
        assertEquals(GateClient.Verdict.UNKNOWN, GateClient.classify(504, "{\"kind\":\"OUTCOME_UNKNOWN\"}"));
        assertEquals(GateClient.Verdict.ERROR, GateClient.classify(400, "{\"status\":\"ERR\"}"));
    }

    @Test
    @DisplayName("o percentil usa a posição de ordem")
    void percentile() {
        long[] sorted = new long[100];
        for (int i = 0; i < sorted.length; i++) {
            sorted[i] = (i + 1) * 1_000_000L;
        }
        assertEquals(99.0, ResilienceGates.percentile(sorted, 0.99));
        assertEquals(0.0, ResilienceGates.percentile(new long[0], 0.99));
    }

    /**
     * Ensaio dos quatro portões numa máquina, com carga mínima, para provar que os roteiros funcionam. Os vereditos
     * de desempenho só valem em máquinas separadas. Ligue com {@code -Daxon.gate.e5=true}.
     */
    @Test
    @EnabledIfSystemProperty(named = "axon.gate.e5", matches = "true")
    @Timeout(900)
    @DisplayName("ensaio local dos quatro portões")
    void rehearsal() throws Exception {
        List<ResilienceNode> agents = new ArrayList<>();
        StringBuilder nodes = new StringBuilder();
        try {
            for (int i = 1; i <= 3; i++) {
                int offset = 300 + i * 10;
                agents.add(new ResilienceNode(Map.of("id", "n" + i, "advertise", "127.0.0.1", "data",
                    dir.resolve("n" + i).toString(), "offset", Integer.toString(offset)), "ensaio"));
                nodes.append(i == 1 ? "" : ",").append("127.0.0.1+").append(offset);
            }
            Map<String, String> options = new HashMap<>(Map.of("nodes", nodes.toString(), "writers", "4",
                "rounds", "2", "between", "3", "settle", "3", "rows", "3000", "seconds", "8", "analysts", "2",
                "gb", "0.01"));
            options.put("store", dir.resolve("arquivo").toString());
            options.put("archive-wait", "20");

            for (String gate : List.of("failover", "upgrade", "qos", "dr")) {
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                boolean passed;
                try (PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8)) {
                    passed = new ResilienceGates("ensaio", options, out).run(gate);
                }
                String text = output.toString(StandardCharsets.UTF_8);
                System.out.print(text);
                assertTrue(text.contains("E5 " + gate + " RESULT"), text);
                // o p99 numa máquina compartilhada não diz nada; os outros são de correção
                assertTrue(passed || gate.equals("qos"), text);
            }
        } finally {
            agents.forEach(ResilienceNode::close);
        }
    }
}
