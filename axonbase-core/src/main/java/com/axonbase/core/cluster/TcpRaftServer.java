package com.axonbase.core.cluster;

import com.axonbase.common.Messages;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Listener TCP isolado do Jetty; permite nós Raft em processos JVM distintos.
 *
 * <p>Uma conexão serve várias requisições, na ordem. As que já chegaram juntas (pipeline) são entregues de
 * uma vez ao {@link Handler#handleAll}, que pode sincronizar o disco uma só vez para todas. Cada uma é avisada a
 * {@link Handler#arrived} assim que chega, antes de esperar as outras.</p>
 */
public final class TcpRaftServer implements AutoCloseable {
    private static final int MAX_BATCH = 64;
    /** Ao passar disto em lotes, o que chegou é atendido: um líder que não para de enviar não adia a resposta. */
    private static final long MAX_BATCH_BYTES = 16L << 20;
    /** Uma conexão de replicação bate a cada 75 ms: calada por tanto tempo, o outro lado sumiu. */
    private static final int IDLE_TIMEOUT_MILLIS = 60_000;

    private final ServerSocket server;
    private final Handler handler;
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "raft-server");
        thread.setDaemon(true);
        return thread;
    });
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private volatile boolean open = true;

    public TcpRaftServer(int port, Handler handler) throws IOException {
        this.server = new ServerSocket();
        this.server.setReuseAddress(true);
        this.server.bind(new InetSocketAddress(port));
        this.handler = handler;
        workers.execute(this::accept);
    }

    public int port() {
        return server.getLocalPort();
    }

    private void accept() {
        while (open) {
            try {
                Socket socket = server.accept();
                connections.add(socket);
                if (!open) {
                    socket.close();
                }
                workers.execute(() -> serve(socket));
            } catch (IOException failure) {
                if (open) {
                    throw new IllegalStateException(Messages.get("cluster_raft_listener_failed"), failure);
                }
            }
        }
    }

    private void serve(Socket socket) {
        try (socket;
             var in = new DataInputStream(new BufferedInputStream(socket.getInputStream(), 1 << 16));
             var out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            socket.setSoTimeout(IDLE_TIMEOUT_MILLIS);
            while (open) {
                List<RaftWire.Request> requests = new ArrayList<>();
                long bytes = receive(in, requests);
                while (requests.size() < MAX_BATCH && bytes < MAX_BATCH_BYTES && in.available() > 0) {
                    bytes += receive(in, requests);
                }
                for (RaftWire.Response response : handler.handleAll(requests)) {
                    RaftWire.write(out, response);
                }
                out.flush();
            }
        } catch (EOFException closedByPeer) {
            // fim normal da conexão
        } catch (IOException disconnected) {
            // o outro lado reconecta
        } catch (RuntimeException failure) {
            if (open) {
                System.getLogger(TcpRaftServer.class.getName()).log(System.Logger.Level.WARNING,
                    "falha ao atender requisição Raft", failure);
            }
        } finally {
            connections.remove(socket);
        }
    }

    /** Lê uma requisição, avisa que ela chegou e devolve o tamanho do lote que ela traz. */
    private long receive(DataInputStream in, List<RaftWire.Request> requests) throws IOException {
        RaftWire.Request request = RaftWire.readRequest(in);
        handler.arrived(request);
        requests.add(request);
        return request.payload().length;
    }

    @Override
    public void close() throws IOException {
        open = false;
        server.close();
        for (Socket socket : connections) {
            socket.close();
        }
        workers.shutdown();
        try {
            // Deixa a requisição em curso terminar antes de o dono fechar o log.
            if (!workers.awaitTermination(5, TimeUnit.SECONDS)) {
                workers.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            workers.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface
    public interface Handler {
        RaftWire.Response handle(RaftWire.Request request);

        /** Uma requisição acabou de chegar e ainda vai ser atendida; não pode bloquear. */
        default void arrived(RaftWire.Request request) {
        }

        /** Atende requisições que chegaram juntas; as respostas saem na mesma ordem. */
        default List<RaftWire.Response> handleAll(List<RaftWire.Request> requests) {
            return requests.stream().map(this::handle).toList();
        }
    }
}
