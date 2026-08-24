package com.axonbase.core.cluster;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** RPC TCP mínimo e binário para RequestVote, AppendEntries e InstallSnapshot. */
public final class RaftWire {
    public static final byte VOTE = 1, APPEND = 2, SNAPSHOT = 3;
    private RaftWire() { }

    public static Response call(InetSocketAddress peer, Request request, int timeoutMillis) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(peer, timeoutMillis); socket.setSoTimeout(timeoutMillis);
            try (var out = new DataOutputStream(socket.getOutputStream()); var in = new DataInputStream(socket.getInputStream())) {
                out.writeByte(request.type()); out.writeUTF(request.group()); out.writeLong(request.term());
                out.writeUTF(request.leaderOrCandidate()); out.writeLong(request.index()); out.writeLong(request.commitIndex());
                out.writeInt(request.payload().length); out.write(request.payload()); out.flush();
                return new Response(in.readLong(), in.readBoolean(), in.readLong());
            }
        }
    }
    public record Request(byte type, String group, long term, String leaderOrCandidate, long index,
                          long commitIndex, byte[] payload) { }
    public record Response(long term, boolean accepted, long matchIndex) { }
}
