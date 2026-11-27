package com.axonbase.core.cluster;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * RPC TCP mínimo e binário do consenso.
 *
 * <p>Cada request carrega o endereço anunciado de quem a envia. É isso que permite a um seguidor responder ao
 * cliente com o endereço do líder, e não apenas com o nome dele.</p>
 *
 * <p>Os tipos 1 a 3 existem desde as primeiras versões. Os demais são oferecidos depois de um {@link #HELLO}
 * aceito: um nó antigo o rejeita e fecha a conexão, e o líder passa a falar só o protocolo antigo com ele.</p>
 */
public final class RaftWire {

    public static final byte VOTE = 1;
    public static final byte APPEND = 2;
    public static final byte SNAPSHOT = 3;
    /** Pergunta se o outro lado aceita conexão persistente com os tipos abaixo. */
    public static final byte HELLO = 4;
    /** AppendEntries com várias entradas (ou nenhuma, como batimento). {@code index} é a entrada anterior. */
    public static final byte APPEND_STREAM = 5;
    /** Um seguidor pede ao líder o índice a partir do qual uma leitura é linearizável. */
    public static final byte READ_INDEX = 6;
    /** Um learner se apresenta ao líder. {@code index} é a última entrada que ele tem. */
    public static final byte JOIN = 7;
    /** O líder pede ao destinatário que abra uma eleição já (transferência de liderança). */
    public static final byte TIMEOUT_NOW = 8;
    /** Pergunta o nível de protocolo, que volta em {@code matchIndex}. Um nó antigo recusa. */
    public static final byte PEER_INFO = 9;

    /** Nível de protocolo deste build: 3 entende {@link #TIMEOUT_NOW} e {@link #PEER_INFO}. */
    public static final int PROTOCOL_LEVEL = 3;

    private static final int MAX_PAYLOAD = 1 << 30;

    private RaftWire() {
    }

    public static Response call(InetSocketAddress peer, Request request, int timeoutMillis)
            throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(peer, Math.min(timeoutMillis, 1_000));
            socket.setSoTimeout(timeoutMillis);
            socket.setTcpNoDelay(true);
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                 DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {
                write(out, request);
                out.flush();
                return readResponse(in);
            }
        }
    }

    static void write(DataOutputStream out, Request request) throws IOException {
        out.writeByte(request.type());
        out.writeUTF(request.group());
        out.writeLong(request.term());
        out.writeUTF(request.leaderOrCandidate());
        out.writeUTF(request.advertise());
        out.writeLong(request.index());
        out.writeLong(request.commitIndex());
        out.writeInt(request.payload().length);
        out.write(request.payload());
    }

    static Request readRequest(DataInputStream in) throws IOException {
        byte type = in.readByte();
        String group = in.readUTF();
        long term = in.readLong();
        String actor = in.readUTF();
        String advertise = in.readUTF();
        long index = in.readLong();
        long commit = in.readLong();
        int length = in.readInt();
        if (length < 0 || length > MAX_PAYLOAD) {
            throw new IOException("payload Raft com tamanho inválido: " + length);
        }
        byte[] payload = in.readNBytes(length);
        if (payload.length != length) {
            throw new java.io.EOFException("payload Raft truncado");
        }
        return new Request(type, group, term, actor, advertise, index, commit, payload);
    }

    static void write(DataOutputStream out, Response response) throws IOException {
        out.writeLong(response.term());
        out.writeBoolean(response.accepted());
        out.writeLong(response.matchIndex());
    }

    static Response readResponse(DataInputStream in) throws IOException {
        return new Response(in.readLong(), in.readBoolean(), in.readLong());
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
