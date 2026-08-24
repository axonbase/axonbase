package com.axonbase.sdk;

import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;

import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.client.WebSocketClient;

import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Cliente SDK de AxonBase sobre WebSocket (JSON-RPC), usando o cliente
 * WebSocket de Jetty. Ofrece {@code use}, {@code query}, {@code create},
 * {@code select}, {@code update} e {@code delete} de xeito síncrono.
 */
public final class Axon implements AutoCloseable {

    private static final String VERSION = "0.1.0-SNAPSHOT";

    private final Map<Integer, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private final AtomicInteger nextId = new AtomicInteger(0);
    private final WebSocketClient client;
    private volatile Session session;

    private Axon(WebSocketClient client) {
        this.client = client;
    }

    public static Axon connect(String url) {
        try {
            WebSocketClient client = new WebSocketClient();
            client.start();
            Axon axon = new Axon(client);
            // 'this' (Axon) extiende WebSocketAdapter, así que é o endpoint da conexión.
            client.connect(new Holder(axon), URI.create(url)).get(10, TimeUnit.SECONDS);
            return axon;
        } catch (Exception e) {
            throw new AxonSdkException("erro ao conectarse a " + url, e);
        }
    }

    public String version() {
        return VERSION;
    }

    public void use(String ns, String db) {
        call("use", new Object[]{ns, db});
    }

    public AxonValue query(String sql) {
        return call("query", new Object[]{sql});
    }

    public AxonValue create(String table, AxonValue content) {
        return query("CREATE " + table + " CONTENT " + AxonJson.write(content));
    }

    public AxonValue select(String sql) {
        return query(sql);
    }

    public AxonValue update(String table, String setClause, String whereClause) {
        return query("UPDATE " + table + " SET " + setClause + " WHERE " + whereClause);
    }

    public AxonValue delete(String table, String whereClause) {
        return query("DELETE " + table + " WHERE " + whereClause);
    }

    public String signin(String user, String pass) {
        AxonValue token = call("signin", new Object[]{Map.of("user", user, "pass", pass)});
        String jwt = token.isString() ? token.asString() : null;
        if (jwt != null) {
            credentials = jwt;
        }
        return jwt;
    }

    /** Valida un JWT no servidor (autenticación explícita). */
    public void authenticate(String token) {
        call("authenticate", new Object[]{token});
    }

    // ------------------------------------------------------------------
    // Live queries
    // ------------------------------------------------------------------

    /** Recetor das mudanças de uma live query. */
    @FunctionalInterface
    public interface LiveHandler {
        /**
         * @param id     identificador da live query
         * @param action CREATE, UPDATE ou DELETE
         * @param result registro afetado (ou as operações de patch, no modo diff)
         */
        void onChange(String id, String action, AxonValue result);
    }

    private final Map<String, LiveHandler> liveHandlers = new ConcurrentHashMap<>();
    private final Map<String, java.util.List<AxonValue>> earlyNotes = new ConcurrentHashMap<>();

    /** Observa uma tabela e devolve o id da live query. */
    public String live(String table, LiveHandler handler) {
        return live(table, false, handler);
    }

    /**
     * Observa uma tabela. Com {@code diff = true}, o resultado de cada mudança
     * é a lista de operações de patch em vez do registro completo.
     */
    public String live(String table, boolean diff, LiveHandler handler) {
        AxonValue id = call("live", new Object[]{table, diff});
        String key = id.isString() ? id.asString() : String.valueOf(id);
        liveHandlers.put(key, handler);
        // notificações que chegaram antes do registro do handler
        java.util.List<AxonValue> early = earlyNotes.remove(key);
        if (early != null) {
            early.forEach(n -> dispatchNotification(key, n));
        }
        return key;
    }

    /** Cancela uma live query. */
    public boolean kill(String id) {
        AxonValue r = call("kill", new Object[]{id});
        liveHandlers.remove(id);
        earlyNotes.remove(id);
        return r.isBool() && r.asBool();
    }

    private void onNotification(AxonValue note) {
        if (!note.isObject()) {
            return;
        }
        AxonValue idv = note.asObject().get("id");
        String id = idv != null && idv.isString() ? idv.asString() : "";
        if (liveHandlers.containsKey(id)) {
            dispatchNotification(id, note);
        } else {
            earlyNotes.computeIfAbsent(id, k -> java.util.Collections.synchronizedList(
                new java.util.ArrayList<>())).add(note);
        }
    }

    private void dispatchNotification(String id, AxonValue note) {
        LiveHandler handler = liveHandlers.get(id);
        if (handler == null) {
            return;
        }
        AxonValue action = note.asObject().get("action");
        AxonValue result = note.asObject().get("result");
        handler.onChange(id, action != null && action.isString() ? action.asString() : "",
            result == null ? AxonValue.nul() : result);
    }

    private String credentials;

    @Override
    public void close() {
        try {
            client.stop();
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------------
    // rexistro da sesión (endpoint pon o handler)
    // ------------------------------------------------------------------

    void install(Session s) {
        this.session = s;
    }

    AxonValue handleText(String text) {
        // frames de live query não têm id de request: {"notification": {...}}
        if (text.startsWith("{\"notification\"")) {
            AxonValue frame = AxonJson.parseDocument(text);
            if (frame.isObject()) {
                onNotification(frame.asObject().getOrDefault("notification", AxonValue.nul()));
            }
            return AxonValue.nul();
        }
        Integer id = idOf(text);
        return pendingResult(id, text);
    }

    // ------------------------------------------------------------------
    // request/response
    // ------------------------------------------------------------------

    private AxonValue call(String method, Object[] params) {
        int id = nextId.getAndIncrement();
        String req = jsonRequest(id, method, params);
        CompletableFuture<String> fut = new CompletableFuture<>();
        pending.put(id, fut);
        Session s = session;
        if (s == null || !s.isOpen()) {
            pending.remove(id);
            throw new AxonSdkException("conexión WebSocket non aberta");
        }
        try {
            s.getRemote().sendString(req);
        } catch (Exception e) {
            pending.remove(id);
            throw new AxonSdkException("erro ao enviar " + method, e);
        }
        try {
            String resp = fut.get(30, TimeUnit.SECONDS);
            return parseResponse(resp);
        } catch (TimeoutException e) {
            throw new AxonSdkException("tempo esgotado na chamada " + method, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AxonSdkException("chamada interrompida", e);
        } catch (ExecutionException e) {
            throw new AxonSdkException("erro de execución en " + method, e);
        } finally {
            pending.remove(id);
        }
    }

    private AxonValue pendingResult(Integer id, String text) {
        if (id == null) {
            return AxonValue.nul();
        }
        CompletableFuture<String> fut = pending.get(id);
        if (fut != null) {
            fut.complete(text);
        }
        return AxonValue.nul();
    }

    private static String jsonRequest(int id, String method, Object[] params) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"id\":").append(id)
            .append(",\"method\":\"").append(method)
            .append("\",\"params\":[");
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            appendValue(sb, params[i]);
        }
        return sb.append("]}").toString();
    }

    private static void appendValue(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
        } else if (v instanceof Number || v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof AxonValue av) {
            sb.append(AxonJson.write(av));
        } else if (v instanceof Map<?, ?> map) {
            sb.append('{');
            boolean first = true;
            for (var e : map.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append('"').append(String.valueOf(e.getKey())).append("\":");
                appendValue(sb, e.getValue());
            }
            sb.append('}');
        } else {
            sb.append('"').append(v).append('"');
        }
    }

    private static AxonValue parseResponse(String resp) {
        AxonValue v = AxonJson.parseDocument(resp);
        if (!v.isObject()) {
            throw new AxonSdkException("resposta RPC non obxecto");
        }
        Map<String, AxonValue> obj = v.asObject();
        if (obj.containsKey("error")) {
            AxonValue err = obj.get("error");
            String msg = err.isObject() && err.asObject().containsKey("message")
                ? err.asObject().get("message").asString()
                : err.toString();
            throw new AxonSdkException("erro do servidor: " + msg);
        }
        return obj.getOrDefault("result", AxonValue.nul());
    }

    private static Integer idOf(String text) {
        int m = text.indexOf("\"id\":");
        if (m < 0) {
            return null;
        }
        int s = m + 5;
        int e = text.indexOf(',', s);
        if (e < 0) {
            e = text.indexOf('}', s);
        }
        try {
            return Integer.parseInt(text.substring(s, e).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Endpoint del cliente: reenvía ao Axon propietario. */
    private static final class Holder extends WebSocketAdapter {
        private final Axon owner;

        Holder(Axon owner) {
            this.owner = owner;
        }

        @Override
        public void onWebSocketConnect(Session session) {
            super.onWebSocketConnect(session);
            owner.install(session);
        }

        @Override
        public void onWebSocketText(String message) {
            owner.handleText(message);
        }
    }
}