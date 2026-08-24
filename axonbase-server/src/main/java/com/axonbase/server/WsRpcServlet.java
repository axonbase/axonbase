package com.axonbase.server;

import com.axonbase.core.engine.LiveBus;
import com.axonbase.value.AxonJson;
import org.eclipse.jetty.websocket.api.Session;
import org.eclipse.jetty.websocket.api.WebSocketAdapter;
import org.eclipse.jetty.websocket.server.JettyWebSocketServlet;
import org.eclipse.jetty.websocket.server.JettyWebSocketServletFactory;

/**
 * Endpoint WebSocket do AxonBase para JSON-RPC ({@code /rpc/ws} upgrade).
 * Cada conexión mantén unha sesión AxonBase propia e despacha os mensaxes
 * ao {@link RpcDispatcher}, usando {@link WebSocketAdapter} (interfaz directa).
 *
 * <p>Além das respostas de RPC, a conexão recebe as notificações das live
 * queries no formato {@code {"notification": {"id", "action", "result"}}}.</p>
 */
public final class WsRpcServlet extends JettyWebSocketServlet {

    private final RpcDispatcher dispatcher;

    public WsRpcServlet(com.axonbase.core.engine.Datastore ds) {
        this.dispatcher = new RpcDispatcher(ds);
    }

    public WsRpcServlet(com.axonbase.core.engine.Datastore ds, com.axonbase.server.auth.AuthService auth,
                        boolean requireAuth) {
        this.dispatcher = new RpcDispatcher(ds, auth, requireAuth);
    }

    @Override
    protected void configure(JettyWebSocketServletFactory factory) {
        factory.setCreator((req, resp) -> new Endpoint(dispatcher));
    }

    /** Endpoint por conexión: unha sesión AxonBase reutilizada. */
    public static final class Endpoint extends WebSocketAdapter {

        private final com.axonbase.core.Session session = com.axonbase.core.Session.create();
        private final RpcDispatcher dispatcher;

        public Endpoint(RpcDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }

        @Override
        public void onWebSocketConnect(Session session) {
            super.onWebSocketConnect(session);
            // as live queries desta sessão passam a empurrar frames para o cliente
            this.session.liveListener(this::push);
        }

        /** Envia uma notificação de live query ao cliente. */
        private void push(LiveBus.Notification note) {
            send("{\"notification\":" + AxonJson.write(note.toValue()) + "}");
        }

        @Override
        public void onWebSocketText(String message) {
            send(dispatcher.dispatch(message, session));
        }

        private void send(String payload) {
            if (!isConnected()) {
                return;
            }
            try {
                getRemote().sendString(payload);
            } catch (Exception e) {
                // conexão caiu no meio do envio: nada a fazer além de desistir
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

        /** Cancela as live queries e a transação pendente da conexão. */
        private void cleanup() {
            session.liveListener(null);
            dispatcher.datastore().liveBus().killAll(session);
            if (session.inTransaction()) {
                dispatcher.datastore().cancelSession(session);
            }
        }
    }
}