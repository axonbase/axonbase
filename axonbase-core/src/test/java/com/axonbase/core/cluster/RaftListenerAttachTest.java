package com.axonbase.core.cluster;

import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * O datastore só é acoplado depois de o nó subir, e o consenso já pode estar aplicando lotes. Nenhum deles pode
 * ficar fora do que o listener recebe, ou o catálogo em memória fica para trás até a próxima definição.
 */
@DisplayName("Cluster: acoplar o listener não perde lote aplicado antes ou durante")
class RaftListenerAttachTest {

    @TempDir
    Path dir;

    private static Set<String> attach(RaftNode node) {
        Set<String> seen = ConcurrentHashMap.newKeySet();
        node.listener(applied -> seen.addAll(applied.batch().puts().keySet()));
        node.drainNotifications();
        return seen;
    }

    @Test
    @DisplayName("um snapshot instalado antes do acoplamento chega ao listener, mesmo sem entradas no log")
    void aSnapshotAppliedBeforeAttachingIsDelivered() throws Exception {
        try (RaftNode source = new RaftNode("a", dir.resolve("a"), new MemoryBackend());
             RaftNode target = new RaftNode("b", dir.resolve("b"), new MemoryBackend())) {
            source.appendLocal(1, 1, ClusterHarness.put("antiga", "1"));
            source.appendLocal(1, 2, ClusterHarness.put("recente", "2"));
            source.commitLocal(1, "a", 2);
            RaftNode.Image image = source.image();
            RaftWire.Request request = new RaftWire.Request(RaftWire.SNAPSHOT, "g", 1, "a", image.index(), 2,
                RaftPayload.encode(image.batch()));
            assertTrue(target.handle(request).accepted());

            Set<String> seen = attach(target);

            assertTrue(seen.containsAll(Set.of("antiga", "recente")), "o listener recebeu só " + seen);
        }
    }

    @Test
    @DisplayName("lote aplicado enquanto o listener é acoplado é entregue, na releitura ou depois dela")
    void aBatchAppliedWhileAttachingIsDelivered() throws Exception {
        for (int round = 0; round < 100; round++) {
            try (RaftNode node = new RaftNode("n" + round, dir.resolve("n" + round), new MemoryBackend())) {
                node.appendLocal(1, 1, ClusterHarness.put("primeira", "1"));
                node.commitLocal(1, "n", 1);
                node.appendLocal(1, 2, ClusterHarness.put("durante", "2"));
                CountDownLatch go = new CountDownLatch(1);
                Thread applier = Thread.ofPlatform().start(() -> {
                    try {
                        go.await(10, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    node.commitLocal(1, "n", 2);
                });
                Set<String> seen = ConcurrentHashMap.newKeySet();
                go.countDown();
                node.listener(applied -> seen.addAll(applied.batch().puts().keySet()));
                applier.join(10_000);
                node.drainNotifications();

                assertTrue(seen.containsAll(Set.of("primeira", "durante")),
                    "rodada " + round + ": o listener recebeu só " + seen);
            }
        }
    }
}
