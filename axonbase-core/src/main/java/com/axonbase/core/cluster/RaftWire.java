package com.axonbase.core.cluster;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * RPC TCP mínimo e binário para RequestVote, AppendEntries e InstallSnapshot.
 *
 * <p>Cada request carrega o endereço anunciado de quem a envia. É isso que permite
 * a um seguidor responder ao cliente com o endereço do líder, e não apenas com o
 * nome dele.</p>
 */
public final class RaftWire {

    public static final byte VOTE = 1;
    public static final byte APPEND = 2;
    public static final byte SNAPSHOT = 3;

    private RaftWire() {
    }

    public static Response call(InetSocketAddress peer, Request request, int timeoutMillis)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(peer, timeoutMillis);
            socket.setSoTimeout(timeoutMillis);
            try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {
                out.writeByte(request.type());
                out.writeUTF(request.group());
                out.writeLong(request.term());
                out.writeUTF(request.leaderOrCandidate());
                out.writeUTF(request.advertise());
                out.writeLong(request.index());
                out.writeLong(request.commitIndex());
                out.writeInt(request.payload().length);
                out.write(request.payload());
                out.flush();
                return new Response(in.readLong(), in.readBoolean(), in.readLong());
            }
        }
    }

    /** Formata um endereço na forma {@code host:porta} usada no wire. */
    public static String advertise(InetSocketAddress address) {
        return address == null ? "" : address.getHostString() + ":" + address.getPort();
    }

    public record Request(byte type, String group, long term, String leaderOrCandidate,
                          String advertise, long index, long commitIndex, byte[] payload) {

        public Request {
            advertise = advertise == null ? "" : advertise;
        }

        /** Request sem endereço anunciado, usada por chamadores que não o conhecem. */
        public Request(byte type, String group, long term, String leaderOrCandidate, long index,
                       long commitIndex, byte[] payload) {
            this(type, group, term, leaderOrCandidate, "", index, commitIndex, payload);
        }
    }

    public record Response(long term, boolean accepted, long matchIndex) {
    }
}
