package com.axonbase.core.engine;

import com.axonbase.core.Session;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Barramento de live queries (consultas em tempo real).
 *
 * <p>Cada {@code LIVE SELECT} regista uma {@link Subscription} associada à
 * sessão que a criou. Quando o motor muda um registro da tabela observada,
 * o {@link Executor} pede as subscriptions da tabela, filtra pelo WHERE e
 * entrega uma {@link Notification} ao listener da sessão dona.</p>
 */
public final class LiveBus {

    /** Ações notificadas. */
    public static final String CREATE = "CREATE";
    public static final String UPDATE = "UPDATE";
    public static final String DELETE = "DELETE";

    /** Assinatura viva de uma consulta. */
    public record Subscription(String id, String ns, String db, String table,
                               Statement.Select select, boolean diff, Session owner) {
    }

    /** Notificação entregue ao cliente. */
    public record Notification(String id, String action, AxonValue result) {

        /** Serializa a notificação como objeto AxonValue para o wire. */
        public AxonValue toValue() {
            return AxonValue.object(new java.util.LinkedHashMap<>(Map.of(
                "id", AxonValue.str(id),
                "action", AxonValue.str(action),
                "result", result == null ? AxonValue.nul() : result)));
        }
    }

    /** Recetor de notificações (por exemplo, uma conexão WebSocket). */
    @FunctionalInterface
    public interface Listener {
        void onNotification(Notification notification);
    }

    private final ConcurrentMap<String, Subscription> subscriptions = new ConcurrentHashMap<>();

    /** Regista uma subscription e devolve o identificador da live query. */
    public String register(String ns, String db, String table, Statement.Select select,
                           boolean diff, Session owner) {
        String id = UUID.randomUUID().toString();
        subscriptions.put(id, new Subscription(id, ns, db, table, select, diff, owner));
        if (owner != null) {
            owner.trackLive(id);
        }
        return id;
    }

    /** Cancela uma subscription pelo id. Devolve true se existia. */
    public boolean kill(String id) {
        Subscription removed = subscriptions.remove(id);
        if (removed != null && removed.owner() != null) {
            removed.owner().untrackLive(id);
        }
        return removed != null;
    }

    /** Cancela todas as subscriptions de uma sessão (usado ao fechar a conexão). */
    public void killAll(Session owner) {
        for (Subscription s : List.copyOf(subscriptions.values())) {
            if (s.owner() == owner) {
                subscriptions.remove(s.id());
            }
        }
        if (owner != null) {
            owner.clearLive();
        }
    }

    /** Nenhuma subscription ativa: caminho rápido para o motor. */
    public boolean isEmpty() {
        return subscriptions.isEmpty();
    }

    public int size() {
        return subscriptions.size();
    }

    public Subscription get(String id) {
        return subscriptions.get(id);
    }

    /** Subscriptions ativas para a tabela indicada. */
    public List<Subscription> forTable(String ns, String db, String table) {
        if (subscriptions.isEmpty()) {
            return List.of();
        }
        List<Subscription> out = new ArrayList<>();
        for (Subscription s : subscriptions.values()) {
            if (s.ns().equals(ns) && s.db().equals(db) && s.table().equals(table)) {
                out.add(s);
            }
        }
        return out;
    }

    /** Entrega a notificação ao listener da sessão dona, se houver. */
    public void deliver(Subscription sub, Notification notification) {
        if (sub == null || sub.owner() == null) {
            return;
        }
        Listener listener = sub.owner().liveListener();
        if (listener != null) {
            listener.onNotification(notification);
        }
    }
}