package com.axonbase.core.cluster;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Um enlace de rede entre dois nós, que o teste corta e religa. Cortado, nada passa: as conexões continuam abertas e
 * mudas, como numa partição de verdade, e quem espera resposta precisa desistir pelo próprio prazo.
 */
final class ChaosProxy implements AutoCloseable {

    private final ServerSocket server;
    private final int target;
    private final Object gate = new Object();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    private volatile boolean cut;
    private volatile boolean open = true;

    ChaosProxy(int listenPort, int target) throws IOException {
        this.server = new ServerSocket(listenPort, 128, InetAddress.getLoopbackAddress());
        this.target = target;
        daemon("proxy-accept-" + listenPort, this::accept);
    }

    void cut() {
        cut = true;
    }

    void heal() {
        synchronized (gate) {
            cut = false;
            gate.notifyAll();
        }
    }

    private void accept() {
        while (open) {
            try {
                Socket inbound = server.accept();
                sockets.add(inbound);
                daemon("proxy-connect", () -> connect(inbound));
            } catch (IOException closed) {
                return;
            }
        }
    }

    private void connect(Socket inbound) {
        try {
            awaitHealed();
            Socket outbound = new Socket(InetAddress.getLoopbackAddress(), target);
            sockets.add(outbound);
            daemon("proxy-back", () -> pump(outbound, inbound));
            pump(inbound, outbound);
        } catch (IOException | InterruptedException unreachable) {
            close(inbound);
        }
    }

    private void pump(Socket from, Socket to) {
        try {
            InputStream in = from.getInputStream();
            OutputStream out = to.getOutputStream();
            byte[] buffer = new byte[64 * 1024];
            for (int read = in.read(buffer); read >= 0; read = in.read(buffer)) {
                awaitHealed();
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException | InterruptedException broken) {
            // um dos lados fechou
        } finally {
            close(from);
            close(to);
        }
    }

    private void awaitHealed() throws InterruptedException, IOException {
        synchronized (gate) {
            while (cut && open) {
                gate.wait(100);
            }
        }
        if (!open) {
            throw new IOException("enlace fechado");
        }
    }

    private void close(Socket socket) {
        sockets.remove(socket);
        try {
            socket.close();
        } catch (IOException ignored) {
            // já fechado
        }
    }

    private static void daemon(String name, Runnable work) {
        Thread.ofPlatform().name(name).daemon(true).start(work);
    }

    @Override
    public void close() throws IOException {
        open = false;
        heal();
        server.close();
        Set.copyOf(sockets).forEach(this::close);
    }
}
