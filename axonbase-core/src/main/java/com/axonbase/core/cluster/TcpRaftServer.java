package com.axonbase.core.cluster;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Listener TCP isolado do Jetty; permite nós Raft em processos JVM distintos. */
public final class TcpRaftServer implements AutoCloseable {
    private final ServerSocket server;
    private final Handler handler;
    private final ExecutorService workers = Executors.newCachedThreadPool();
    private volatile boolean open = true;
    public TcpRaftServer(int port, Handler handler) throws IOException {
        this.server = new ServerSocket(port); this.handler = handler;
        workers.execute(this::accept);
    }
    public int port() { return server.getLocalPort(); }
    private void accept() {
        while (open) try { Socket socket = server.accept(); workers.execute(() -> handle(socket)); }
        catch (IOException ignored) { if (open) throw new IllegalStateException("listener Raft falhou", ignored); }
    }
    private void handle(Socket socket) {
        try (socket; var in = new DataInputStream(socket.getInputStream()); var out = new DataOutputStream(socket.getOutputStream())) {
            byte type=in.readByte(); String group=in.readUTF(); long term=in.readLong(); String actor=in.readUTF();
            String advertise=in.readUTF(); long index=in.readLong(); long commit=in.readLong();
            byte[] payload=in.readNBytes(in.readInt()); RaftWire.Response response=handler.handle(new RaftWire.Request(type,group,term,actor,advertise,index,commit,payload));
            out.writeLong(response.term()); out.writeBoolean(response.accepted()); out.writeLong(response.matchIndex()); out.flush();
        } catch (IOException ignored) { }
    }
    @Override public void close() throws IOException { open=false; server.close(); workers.shutdownNow(); }
    @FunctionalInterface public interface Handler { RaftWire.Response handle(RaftWire.Request request); }
}
