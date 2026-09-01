package com.axonbase.server;

import com.axonbase.common.Messages;
import com.axonbase.core.engine.LiveBus;
import com.axonbase.value.AxonJson;
import com.axonbase.value.AxonValue;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;

import java.time.Duration;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

public final class WsRpcServlet extends JettyWebSocketServlet {

    private final RpcDispatcher dispatcher;
    private final AtomicLong openConnections;
    private final AtomicLong framesReceived;
    private final AtomicLong framesSent;
    private final AtomicLong responseTimeNanos;
    private final AtomicLong responseCount;
    private final AtomicLong queryErrors;
    private final AtomicLong notLeaderErrors;
    private final long queryTimeoutMs;
    private final long txnTimeoutMs;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ws-rpc");
        t.setDaemon(true);
        return t;
    });

    public WsRpcServlet(com.axonbase.core.engine.Datastore ds) {
        this(ds, null, false, new AtomicLong(), new AtomicLong(), new AtomicLong(),
            new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong(), 30000L, 300000L);
    }

    public WsRpcServlet(com.axonbase.core.engine.Datastore ds, com.axonbase.server.auth.AuthService auth,
                        boolean requireAuth) {
        this(ds, auth, requireAuth, new AtomicLong(), new AtomicLong(), new AtomicLong(),
            new AtomicLong(), new AtomicLong(), new AtomicLong(), new AtomicLong(), 30000L, 300000L);
    }

    WsRpcServlet(com.axonbase.core.engine.Datastore ds, com.axonbase.server.auth.AuthService auth,
                 boolean requireAuth, AtomicLong openConnections,
                 AtomicLong framesReceived, AtomicLong framesSent,
                 AtomicLong responseTimeNanos, AtomicLong responseCount,
                 AtomicLong queryErrors, AtomicLong notLeaderErrors,
                 long queryTimeoutMs, long txnTimeoutMs) {
        this.dispatcher = new RpcDispatcher(ds, auth, requireAuth);
        this.openConnections = openConnections;
        this.framesReceived = framesReceived;
        this.framesSent = framesSent;
        this.responseTimeNanos = responseTimeNanos;
        this.responseCount = responseCount;
        this.queryErrors = queryErrors;
        this.notLeaderErrors = notLeaderErrors;
        this.queryTimeoutMs = queryTimeoutMs;
        this.txnTimeoutMs = txnTimeoutMs;
    }

    WsRpcServlet(com.axonbase.core.engine.Datastore ds, com.axonbase.server.auth.AuthService auth,
                 boolean requireAuth, com.axonbase.server.auth.TrustStoreRegistry trustStores,
                 AtomicLong openConnections, AtomicLong framesReceived, AtomicLong framesSent,
                 AtomicLong responseTimeNanos, AtomicLong responseCount,
                 AtomicLong queryErrors, AtomicLong notLeaderErrors,
                 long queryTimeoutMs, long txnTimeoutMs) {
        this.dispatcher = new RpcDispatcher(ds, auth, requireAuth, 0, trustStores);
        this.openConnections = openConnections;
        this.framesReceived = framesReceived;
        this.framesSent = framesSent;
        this.responseTimeNanos = responseTimeNanos;
        this.responseCount = responseCount;
        this.queryErrors = queryErrors;
        this.notLeaderErrors = notLeaderErrors;
        this.queryTimeoutMs = queryTimeoutMs;
        this.txnTimeoutMs = txnTimeoutMs;
    }

    @Override
    protected void configure(JettyWebSocketServletFactory factory) {
        factory.setIdleTimeout(Duration.ofMillis(txnTimeoutMs));
        factory.setCreator((req, resp) -> new Endpoint(dispatcher, openConnections,
            framesReceived, framesSent, responseTimeNanos, responseCount,
            queryErrors, notLeaderErrors, queryTimeoutMs, executor));
    }

    RpcDispatcher dispatcher() {
        return dispatcher;
    }

    public static final class Endpoint extends WebSocketAdapter {

        private final com.axonbase.core.Session session = com.axonbase.core.Session.create();
        private final RpcDispatcher dispatcher;
        private final AtomicLong openConnections;
        private final AtomicBoolean countedOpen = new AtomicBoolean();
        private final AtomicLong framesReceived;
        private final AtomicLong framesSent;
        private final AtomicLong responseTimeNanos;
        private final AtomicLong responseCount;
        private final AtomicLong queryErrors;
        private final AtomicLong notLeaderErrors;
        private final long queryTimeoutMs;
        private final ExecutorService executor;

        public Endpoint(RpcDispatcher dispatcher, AtomicLong openConnections,
                        AtomicLong framesReceived, AtomicLong framesSent,
                        AtomicLong responseTimeNanos, AtomicLong responseCount,
                        AtomicLong queryErrors, AtomicLong notLeaderErrors,
                        long queryTimeoutMs, ExecutorService executor) {
            this.dispatcher = dispatcher;
            this.openConnections = openConnections;
            this.framesReceived = framesReceived;
            this.framesSent = framesSent;
            this.responseTimeNanos = responseTimeNanos;
            this.responseCount = responseCount;
            this.queryErrors = queryErrors;
            this.notLeaderErrors = notLeaderErrors;
            this.queryTimeoutMs = queryTimeoutMs;
            this.executor = executor;
        }

        @Override
        public void onWebSocketConnect(Session session) {
            super.onWebSocketConnect(session);
            openConnections.incrementAndGet();
            countedOpen.set(true);
            send(dispatcher.hello());
            this.session.liveListener(this::push);
        }

        private void push(LiveBus.Notification note) {
            send("{\"notification\":" + AxonJson.write(note.toValue()) + "}");
        }

        @Override
        public void onWebSocketText(String message) {
            framesReceived.incrementAndGet();
            long startNanos = System.nanoTime();
            String response;
            if (queryTimeoutMs > 0) {
                Future<String> future = executor.submit(() -> dispatcher.dispatch(message, session));
                try {
                    response = future.get(queryTimeoutMs, TimeUnit.MILLISECONDS);
                } catch (TimeoutException e) {
                    future.cancel(true);
                    response = timeoutError(message);
                } catch (ExecutionException e) {
                    response = rpcError(message, Messages.get("rpc_internal_error", e.getCause().getMessage()));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    response = timeoutError(message);
                }
            } else {
                response = dispatcher.dispatch(message, session);
            }
            long elapsed = System.nanoTime() - startNanos;
            responseTimeNanos.addAndGet(elapsed);
            responseCount.incrementAndGet();
            if (response.contains("\"error\"")) {
                if (response.contains("NOT_LEADER")) {
                    notLeaderErrors.incrementAndGet();
                } else {
                    queryErrors.incrementAndGet();
                }
            }
            send(response);
        }

        private String timeoutError(String message) {
            AxonValue id = extractId(message);
            return "{\"id\":" + (id == null ? "null" : AxonJson.write(id))
                + ",\"error\":{\"code\":-32028,\"message\":\"" + esc(Messages.get("query_timeout")) + "\"}}";
        }

        private String rpcError(String message, String detail) {
            AxonValue id = extractId(message);
            return "{\"id\":" + (id == null ? "null" : AxonJson.write(id))
                + ",\"error\":{\"code\":-32003,\"message\":\"" + esc(detail) + "\"}}";
        }

        private static AxonValue extractId(String message) {
            try {
                AxonValue v = AxonJson.parseDocument(message);
                if (v.isObject() && v.asObject().containsKey("id")) {
                    return v.asObject().get("id");
                }
            } catch (Exception ignored) {}
            return null;
        }

        private static String esc(String s) {
            if (s == null) return "";
            return s.replace("\\", "\\\\").replace("\"", "\\\"");
        }

        private void send(String payload) {
            if (!isConnected()) {
                return;
            }
            try {
                getRemote().sendString(payload);
                framesSent.incrementAndGet();
            } catch (Exception e) {
                session.liveListener(null);
            }
        }

        @Override
        public void onWebSocketClose(int status, String reason) {
            cleanup();
            super.onWebSocketClose(status, reason);
        }

        @Override
        public void onWebSocketError(Throwable cause) {
            cleanup();
            super.onWebSocketError(cause);
        }

        private void cleanup() {
            if (countedOpen.compareAndSet(true, false)) {
                openConnections.decrementAndGet();
            }
            session.liveListener(null);
            dispatcher.datastore().liveBus().killAll(session);
            if (session.inTransaction()) {
                dispatcher.datastore().cancelSession(session);
            }
        }
    }
}
