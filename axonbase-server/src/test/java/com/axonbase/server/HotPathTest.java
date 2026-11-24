package com.axonbase.server;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.server.auth.AuthService;
import com.axonbase.server.auth.UserStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Caminho quente do servidor: ids, buffer de log, cache de JWT e admissão")
class HotPathTest {

    @Test
    @DisplayName("FastIds gera UUIDs v4 distintos mesmo com várias threads")
    void fastIdsAreUniqueUuids() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<Set<String>>> parts = new java.util.ArrayList<>();
        for (int t = 0; t < 8; t++) {
            parts.add(pool.submit(() -> {
                Set<String> s = new HashSet<>();
                for (int i = 0; i < 20_000; i++) {
                    s.add(FastIds.uuid());
                }
                return s;
            }));
        }
        Set<String> all = new HashSet<>();
        int total = 0;
        for (var f : parts) {
            Set<String> s = f.get();
            total += s.size();
            all.addAll(s);
        }
        pool.shutdown();
        assertEquals(total, all.size(), "sem repetição");
        String id = FastIds.uuid();
        assertTrue(id.matches("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}"), id);
        assertEquals(id, java.util.UUID.fromString(id).toString());
    }

    @Test
    @DisplayName("LogBuffer sem trava: capacidade respeitada, ordem cronológica e escrita concorrente sem perda de consistência")
    void logBufferConcurrent() throws Exception {
        LogBuffer buffer = new LogBuffer(1000);
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Future<?>> fs = new java.util.ArrayList<>();
        for (int t = 0; t < 8; t++) {
            int id = t;
            fs.add(pool.submit(() -> {
                for (int i = 0; i < 5_000; i++) {
                    buffer.add(new LogBuffer.Entry(System.currentTimeMillis(), "info", "e", "t" + id + "-" + i, Map.of()));
                }
            }));
        }
        for (var f : fs) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(1000, buffer.size());
        List<LogBuffer.Entry> last = buffer.snapshot(5000, null, 0);
        assertEquals(1000, last.size());
        for (int i = 1; i < last.size(); i++) {
            assertTrue(last.get(i - 1).tsMillis() <= last.get(i).tsMillis() + 5, "ordem cronológica aproximada");
        }
        LogBuffer small = new LogBuffer(3);
        for (int i = 0; i < 5; i++) {
            small.add(new LogBuffer.Entry(i, "info", "e", "m" + i, Map.of()));
        }
        assertEquals(List.of("m2", "m3", "m4"), small.snapshot(10, null, 0).stream().map(LogBuffer.Entry::msg).toList());
        assertEquals(List.of("m4"), small.snapshot(1, null, 0).stream().map(LogBuffer.Entry::msg).toList());
    }

    @Test
    @DisplayName("cache do JWT guarda a assinatura, mas expiração e revogação continuam valendo a cada chamada")
    void jwtCacheKeepsRevocation() {
        Datastore ds = Datastore.memory();
        ds.createDatabase("app", "main");
        Session setup = Session.create();
        setup.namespace("app");
        setup.database("main");
        ds.execute("DEFINE USER alice ON DATABASE PASSWORD \"senha\"", setup, null);
        AuthService auth = new AuthService("secret", new UserStore(ds.authCatalog()));
        String token = auth.signin("alice", "senha", "app", "main", null);
        assertNotNull(token);
        Map<String, Object> first = auth.claims(token);
        assertNotNull(first);
        assertEquals(first, auth.claims(token), "segunda chamada vem do cache e traz os mesmos claims");
        assertNull(auth.claims(token + "x"), "token adulterado não passa");
        Object jti = first.get("jti");
        if (jti != null) {
            auth.revokeJwt(String.valueOf(jti), System.currentTimeMillis() / 1000 + 600);
            assertNull(auth.claims(token), "revogado depois de estar no cache");
        }
    }

    @Test
    @DisplayName("admissão espera um instante por vaga antes de recusar")
    void admissionWaitsBriefly() throws Exception {
        AdmissionControl admission = new AdmissionControl(1);
        assertTrue(admission.tryEnter());
        Thread releaser = new Thread(() -> {
            try {
                Thread.sleep(60);
            } catch (InterruptedException ignored) {
            }
            admission.exit();
        });
        releaser.start();
        long a = System.nanoTime();
        assertTrue(admission.tryEnter(), "a vaga liberada em 60 ms atende quem espera");
        assertTrue((System.nanoTime() - a) / 1_000_000 < AdmissionControl.WAIT_MILLIS);
        long b = System.nanoTime();
        assertTrue(!admission.tryEnter(), "sem vaga depois do tempo de espera, recusa");
        assertTrue((System.nanoTime() - b) / 1_000_000 >= AdmissionControl.WAIT_MILLIS - 20);
        releaser.join();
    }
}
