package com.axonbase.server;

import com.axonbase.value.AxonJson;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;

/**
 * Barramento de eventos de gestão. Cada evento sai como uma linha JSON
 * {@code {"type","time","data"}}; assinantes (SSE, webhook) recebem o texto pronto.
 * Linhas que começam com {@code :} são comentários de keepalive e não são eventos.
 */
public final class EventBus {

    private final Set<Consumer<String>> subscribers = new CopyOnWriteArraySet<>();

    /** Assina o barramento. Fechar o resultado cancela a assinatura. */
    public AutoCloseable subscribe(Consumer<String> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    /** Publica um evento. Falha de um assinante não afeta os demais. */
    public void publish(String type, Map<String, ?> data) {
        String json = AxonJson.write(AdminJson.obj("type", type, "time", Instant.now().toString(), "data", data));
        deliver(json);
    }

    /** Comentário de keepalive para manter conexões SSE vivas atrás de proxies. */
    public void heartbeat() {
        deliver(": keepalive");
    }

    public int subscribers() {
        return subscribers.size();
    }

    private void deliver(String line) {
        for (Consumer<String> s : subscribers) {
            try {
                s.accept(line);
            } catch (RuntimeException e) {
                subscribers.remove(s);
            }
        }
    }
}
