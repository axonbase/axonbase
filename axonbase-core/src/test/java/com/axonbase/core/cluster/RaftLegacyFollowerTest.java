package com.axonbase.core.cluster;

import com.axonbase.core.storage.MemoryBackend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import static com.axonbase.core.cluster.ClusterHarness.await;
import static com.axonbase.core.cluster.ClusterHarness.write;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Um líder desta versão sobre um seguidor da 0.10.1. O log antigo, depois de truncar, ainda acha em memória as
 * entradas apagadas: responde que aceitou sem gravar, mas informa o último índice que de fato tem.
 */
@DisplayName("Raft: líder novo sobre seguidor antigo")
@Timeout(120)
class RaftLegacyFollowerTest {

    @TempDir
    Path dir;

    @Test
    @DisplayName("um aceite sem gravação não conta para o quórum, e um mandato novo resolve")
    void acceptanceWithoutStoringDoesNotCount() throws Exception {
        List<Integer> ports = ClusterHarness.reservePorts(3);
        List<InetSocketAddress> addresses = ports.stream().map(p -> new InetSocketAddress("127.0.0.1", p)).toList();
        AtomicLong last = new AtomicLong();
        // o mandato em que as sobras em memória têm o mesmo termo do líder: nele o membro aceita sem gravar
        AtomicLong lyingTerm = new AtomicLong(-1);
        Set<String> stored = ConcurrentHashMap.newKeySet();
        // o terceiro membro fica parado: a maioria depende do nó antigo
        TcpRaftServer.Handler old = request -> {
            if (request.type() == RaftWire.VOTE) {
                return new RaftWire.Response(request.term(), true, last.get());
            }
            if (request.type() == RaftWire.SNAPSHOT) {
                stored.addAll(RaftPayload.decode(request.payload()).puts().keySet());
                last.set(request.index());
                return new RaftWire.Response(request.term(), true, last.get());
            }
            if (request.type() != RaftWire.APPEND) {
                return new RaftWire.Response(request.term(), false, last.get());
            }
            if (request.payload().length == 0) {
                return new RaftWire.Response(request.term(), true, last.get());
            }
            lyingTerm.compareAndSet(-1, request.term());
            if (request.term() != lyingTerm.get()) {
                CommittedBatch batch;
                try {
                    batch = RaftPayload.decodeAppend(request.payload()).batch();
                } catch (IllegalArgumentException unframed) {
                    batch = RaftPayload.decode(request.payload());
                }
                stored.addAll(batch.puts().keySet());
                last.set(request.index());
            }
            return new RaftWire.Response(request.term(), true, last.get());
        };
        try (TcpRaftServer server = new TcpRaftServer(ports.get(2), old);
             ClusterRuntime leader = new ClusterRuntime(new ClusterConfig("n1", "g", addresses.get(0),
                 List.of(addresses.get(1), addresses.get(2))), dir.resolve("n1"), new MemoryBackend())) {
            await("sem líder", 30_000, () -> leader.role() == ClusterRole.LEADER);
            for (int i = 0; i < 20; i++) {
                String key = "k" + i;
                // sem confirmação o desfecho é desconhecido, e o teste repete; confirmada, tem de estar no membro
                await("a escrita " + key + " não se confirmou", 60_000, () -> {
                    try {
                        write(leader, key, "v");
                        return true;
                    } catch (RuntimeException notConfirmed) {
                        return false;
                    }
                });
                assertTrue(stored.contains(key), "confirmada sem estar no membro: " + key);
            }
            assertTrue(leader.electionsStarted() > 1, "o líder não abriu um mandato novo");
        }
    }
}
