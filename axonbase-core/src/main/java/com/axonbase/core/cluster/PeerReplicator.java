package com.axonbase.core.cluster;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Replicação do líder para um seguidor ou learner, numa thread própria.
 *
 * <p>As entradas pendentes vão agrupadas num AppendEntries, e várias requisições ficam em voo na mesma
 * conexão (pipeline), sem esperar a resposta da anterior. Uma rejeição derruba a conexão e recomeça do ponto
 * que o seguidor indicar. Sem entradas, a mesma requisição vazia serve de batimento.</p>
 */
final class PeerReplicator {

    static final long HEARTBEAT_NANOS = TimeUnit.MILLISECONDS.toNanos(75);
    private static final int CONNECT_TIMEOUT_MILLIS = 250;
    /** Um seguidor que passa disso sem responder (disco travado, rede partida) é reconectado. */
    private static final int RPC_TIMEOUT_MILLIS = 10_000;
    private static final int SNAPSHOT_TIMEOUT_MILLIS = 120_000;
    private static final long MAX_BACKOFF_MILLIS = 500;
    private static final long LEGACY_PROBE_NANOS = TimeUnit.SECONDS.toNanos(10);

    /** O que o replicador precisa do líder, válido por um mandato. */
    interface Leader {
        long term();

        long commitIndex();

        RaftNode node();

        RaftOptions options();

        String group();

        String nodeId();

        String advertise();

        /** O índice conferido ou o último contato deste peer mudou. */
        void acknowledged(PeerReplicator peer);

        void higherTerm(long term);

        void snapshotSent(long bytes);
    }

    private record Flight(long previous, long sentNanos) {
    }

    private final InetSocketAddress address;
    private final boolean voter;
    private final Leader leader;
    private final ReentrantLock lock = new ReentrantLock();
    private final Condition signal = lock.newCondition();
    private final Thread thread;
    private volatile boolean running = true;
    private volatile Socket socket;
    private volatile long next;
    private volatile long match;
    private volatile long lastReplyNanos;
    private volatile boolean replied;
    /** Momento do envio da requisição mais recente que este peer respondeu neste mandato. */
    private volatile long lastAckedSendNanos;
    private volatile boolean acked;
    private volatile boolean needsBackup;
    private boolean heartbeatRequested = true;

    PeerReplicator(InetSocketAddress address, boolean voter, long next, Leader leader) {
        this.address = address;
        this.voter = voter;
        this.next = Math.max(1, next);
        this.leader = leader;
        this.thread = new Thread(this::run, "raft-repl-" + address.getPort());
        this.thread.setDaemon(true);
    }

    void start() {
        thread.start();
    }

    InetSocketAddress address() {
        return address;
    }

    boolean voter() {
        return voter;
    }

    long matchIndex() {
        return match;
    }

    /** O learner precisa de um backup físico mais novo: o log já não tem o que falta. */
    boolean needsBackup() {
        return needsBackup;
    }

    /** O peer conta como contatado agora (votou, ou acabou de ser incluído). */
    void contacted() {
        lastReplyNanos = System.nanoTime();
        replied = true;
    }

    /** Nanos desde a última resposta, ou -1 se nunca respondeu. */
    long nanosSinceReply() {
        return replied ? System.nanoTime() - lastReplyNanos : -1;
    }

    /** O peer respondeu a uma requisição enviada depois de {@code nanos}. */
    boolean ackedSince(long nanos) {
        return acked && lastAckedSendNanos - nanos >= 0;
    }

    /** Há entradas ou um novo commit para enviar. */
    void wake() {
        lock.lock();
        try {
            signal.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void requestHeartbeat() {
        lock.lock();
        try {
            heartbeatRequested = true;
            signal.signalAll();
        } finally {
            lock.unlock();
        }
    }

    void stop() {
        running = false;
        closeQuietly(socket);
        wake();
    }

    /** Espera a thread terminar depois de {@link #stop()}. */
    void join(long millis) throws InterruptedException {
        thread.join(millis);
    }

    boolean finished() {
        return thread.getState() == Thread.State.TERMINATED;
    }

    private void run() {
        long backoff = 10;
        while (running) {
            try {
                if (serve()) {
                    backoff = 10;
                }
            } catch (IOException unreachable) {
                // Peer fora do ar apenas não conta para o quórum.
            } catch (RuntimeException failure) {
                if (running) {
                    System.getLogger(PeerReplicator.class.getName()).log(System.Logger.Level.WARNING,
                        "replicação para " + address + " interrompida", failure);
                }
            }
            if (running) {
                pause(backoff);
                backoff = Math.min(backoff * 2, MAX_BACKOFF_MILLIS);
            }
        }
    }

    private void pause(long millis) {
        long remaining = TimeUnit.MILLISECONDS.toNanos(millis);
        lock.lock();
        try {
            while (running && remaining > 0) {
                remaining = signal.awaitNanos(remaining);
            }
        } catch (InterruptedException interrupted) {
            running = false;
        } finally {
            lock.unlock();
        }
    }

    /** Uma conexão: negocia o protocolo e replica até falhar. Devolve se houve progresso. */
    private boolean serve() throws IOException {
        try (Socket connection = new Socket()) {
            socket = connection;
            connection.connect(address, CONNECT_TIMEOUT_MILLIS);
            connection.setTcpNoDelay(true);
            connection.setSoTimeout(RPC_TIMEOUT_MILLIS);
            if (!running) {
                return false;
            }
            var out = new DataOutputStream(new BufferedOutputStream(connection.getOutputStream(), 1 << 16));
            var in = new DataInputStream(new BufferedInputStream(connection.getInputStream()));
            long sent = System.nanoTime();
            RaftWire.write(out, request(RaftWire.HELLO, 0, leader.commitIndex(), new byte[0]));
            out.flush();
            RaftWire.Response hello = RaftWire.readResponse(in);
            if (superseded(hello)) {
                return false;
            }
            answered(sent);
            if (!hello.accepted()) {
                legacy();
                return true;
            }
            return stream(connection, in, out);
        } finally {
            socket = null;
        }
    }

    private boolean superseded(RaftWire.Response response) {
        if (response.term() > leader.term()) {
            leader.higherTerm(response.term());
            return true;
        }
        return false;
    }

    private void answered(long sentNanos) {
        contacted();
        if (!acked || sentNanos - lastAckedSendNanos > 0) {
            lastAckedSendNanos = sentNanos;
        }
        acked = true;
        leader.acknowledged(this);
    }

    private RaftWire.Request request(byte type, long index, long commit, byte[] payload) {
        return new RaftWire.Request(type, leader.group(), leader.term(), leader.nodeId(), leader.advertise(), index,
            commit, payload);
    }

    // ---- protocolo com pipeline ----

    /** Estado de uma conexão em pipeline, protegido por {@code lock}. */
    private static final class Pipeline {
        final ArrayDeque<Flight> flights = new ArrayDeque<>();
        boolean broken;
        long rewindTo;
        boolean progressed;
    }

    private boolean stream(Socket connection, DataInputStream in, DataOutputStream out) throws IOException {
        Pipeline pipeline = new Pipeline();
        Thread reader = new Thread(() -> readResponses(connection, in, pipeline), thread.getName() + "-ack");
        reader.setDaemon(true);
        reader.start();
        try {
            long sentCommit = -1;
            long lastSend = System.nanoTime() - HEARTBEAT_NANOS;
            RaftNode node = leader.node();
            while (running) {
                boolean snapshot;
                lock.lock();
                try {
                    while (running && !pipeline.broken && !due(node, pipeline, sentCommit, lastSend)) {
                        long wait = HEARTBEAT_NANOS - (System.nanoTime() - lastSend);
                        signal.awaitNanos(wait > 0 ? wait : HEARTBEAT_NANOS);
                    }
                    if (!running || pipeline.broken) {
                        break;
                    }
                    heartbeatRequested = false;
                    snapshot = next <= node.baseIndex();
                    if (snapshot && !pipeline.flights.isEmpty()) {
                        signal.awaitNanos(HEARTBEAT_NANOS);
                        continue;
                    }
                } catch (InterruptedException interrupted) {
                    running = false;
                    break;
                } finally {
                    lock.unlock();
                }
                if (snapshot) {
                    sendSnapshot();
                    lastSend = System.nanoTime();
                    continue;
                }
                long previous = next - 1;
                long previousTerm = node.lastTermAt(previous);
                List<RaftStream.Entry> entries = collect(node);
                if (entries == null) {
                    continue; // compactado entre a checagem e a leitura: o próximo giro manda o snapshot
                }
                long commit = leader.commitIndex();
                RaftWire.Request request = request(RaftWire.APPEND_STREAM, previous, commit,
                    RaftStream.encode(previousTerm, entries));
                lastSend = System.nanoTime();
                lock.lock();
                try {
                    pipeline.flights.add(new Flight(previous, lastSend));
                    next = previous + entries.size() + 1;
                } finally {
                    lock.unlock();
                }
                sentCommit = commit;
                RaftWire.write(out, request);
                out.flush();
            }
        } finally {
            lock.lock();
            try {
                pipeline.broken = true;
            } finally {
                lock.unlock();
            }
            closeQuietly(connection);
            join(reader);
            // O que ficou sem resposta é reenviado na próxima conexão.
            if (pipeline.rewindTo > 0) {
                next = pipeline.rewindTo;
            } else if (!pipeline.flights.isEmpty()) {
                next = pipeline.flights.peek().previous() + 1;
            }
        }
        return pipeline.progressed;
    }

    private boolean due(RaftNode node, Pipeline pipeline, long sentCommit, long lastSend) {
        if (pipeline.flights.size() >= leader.options().maxInflightRequests()) {
            return false;
        }
        return next <= node.lastIndex() || heartbeatRequested || leader.commitIndex() > sentCommit
            || System.nanoTime() - lastSend >= HEARTBEAT_NANOS;
    }

    private List<RaftStream.Entry> collect(RaftNode node) {
        RaftOptions options = leader.options();
        List<RaftStream.Entry> entries = new ArrayList<>();
        long bytes = 0;
        long last = node.lastIndex();
        for (long i = next; i <= last && entries.size() < options.maxEntriesPerRequest(); i++) {
            long term = node.lastTermAt(i);
            byte[] payload = node.encoded(i);
            if (payload == null) {
                return entries.isEmpty() ? null : entries;
            }
            if (!entries.isEmpty() && bytes + payload.length > options.maxBytesPerRequest()) {
                break;
            }
            bytes += payload.length;
            entries.add(new RaftStream.Entry(term, payload));
        }
        return entries;
    }

    private void readResponses(Socket connection, DataInputStream in, Pipeline pipeline) {
        try {
            while (running) {
                RaftWire.Response response = RaftWire.readResponse(in);
                if (superseded(response)) {
                    break;
                }
                Flight flight;
                lock.lock();
                try {
                    flight = pipeline.flights.poll();
                    if (flight == null) {
                        break;
                    }
                    // Uma recusa também é conversa normal: reconecta sem aumentar a espera.
                    pipeline.progressed = true;
                    if (response.accepted()) {
                        match = Math.max(match, response.matchIndex());
                    } else {
                        // O seguidor diz até onde pode seguir: antes do anterior quando os logs divergem, ou
                        // logo antes da entrada que ele recusou (um carimbo adiantado, por exemplo).
                        pipeline.rewindTo = response.matchIndex() + 1;
                        pipeline.broken = true;
                    }
                    signal.signalAll();
                } finally {
                    lock.unlock();
                }
                answered(flight.sentNanos());
                if (!response.accepted()) {
                    break;
                }
            }
        } catch (IOException disconnected) {
            // a thread de escrita reconecta
        } finally {
            lock.lock();
            try {
                pipeline.broken = true;
                signal.signalAll();
            } finally {
                lock.unlock();
            }
            closeQuietly(connection);
        }
    }

    // ---- snapshot ----

    /**
     * Envia o estado inteiro a um membro que o log já não alcança. Um learner não recebe: ele entra a partir de
     * um backup físico.
     */
    private void sendSnapshot() throws IOException {
        if (!voter) {
            needsBackup = true;
            long sent = System.nanoTime();
            // Continua batendo, para o learner saber quem é o líder e que está atrasado.
            RaftWire.Response response = RaftWire.call(address, request(RaftWire.APPEND_STREAM,
                leader.node().lastIndex(), leader.commitIndex(),
                RaftStream.encode(leader.node().lastTerm(), List.of())), RPC_TIMEOUT_MILLIS);
            if (!superseded(response)) {
                answered(sent);
            }
            pause(MAX_BACKOFF_MILLIS);
            return;
        }
        RaftNode.Image image = leader.node().image();
        byte[] payload = RaftPayload.encode(image.batch());
        leader.snapshotSent(payload.length);
        long sent = System.nanoTime();
        RaftWire.Response response = RaftWire.call(address,
            request(RaftWire.SNAPSHOT, image.index(), leader.commitIndex(), payload), SNAPSHOT_TIMEOUT_MILLIS);
        if (superseded(response)) {
            return;
        }
        if (response.accepted()) {
            match = Math.max(match, image.index());
            next = image.index() + 1;
            answered(sent);
        } else {
            pause(MAX_BACKOFF_MILLIS);
        }
    }

    // ---- nó antigo ----

    /**
     * Protocolo das versões até a 0.10.x: uma entrada por conexão. O nó antigo grava cada entrada com o termo
     * da requisição, então o termo anterior informado precisa ser o que foi gravado lá.
     */
    private void legacy() throws IOException {
        Map<Long, Long> storedThere = new HashMap<>();
        RaftNode node = leader.node();
        long until = System.nanoTime() + LEGACY_PROBE_NANOS;
        long lastSend = System.nanoTime() - HEARTBEAT_NANOS;
        boolean rejectedAtStart = false;
        while (running && System.nanoTime() < until) {
            if (next <= node.baseIndex() || rejectedAtStart) {
                rejectedAtStart = false;
                sendSnapshot();
                continue;
            }
            long sent = System.nanoTime();
            if (next <= node.lastIndex()) {
                long index = next;
                FileRaftLog.Entry entry = node.entry(index);
                if (entry == null) {
                    continue;
                }
                long previousTerm = storedThere.getOrDefault(index - 1, node.lastTermAt(index - 1));
                RaftWire.Response response = RaftWire.call(address,
                    new RaftWire.Request(RaftWire.APPEND, leader.group(), leader.term(), leader.nodeId(),
                        leader.advertise(), index, Math.min(leader.commitIndex(), match),
                        RaftPayload.append(index - 1, previousTerm, entry.batch())), RPC_TIMEOUT_MILLIS);
                if (superseded(response)) {
                    return;
                }
                lastSend = sent;
                if (response.accepted()) {
                    storedThere.put(index, leader.term());
                    storedThere.remove(index - 64);
                    match = Math.max(match, index);
                    next = index + 1;
                } else if (index == 1) {
                    rejectedAtStart = true;
                } else {
                    next = index - 1;
                }
                answered(sent);
            } else if (sent - lastSend >= HEARTBEAT_NANOS) {
                RaftWire.Response response = RaftWire.call(address,
                    request(RaftWire.APPEND, 0, Math.min(leader.commitIndex(), match), new byte[0]),
                    RPC_TIMEOUT_MILLIS);
                if (superseded(response)) {
                    return;
                }
                lastSend = sent;
                answered(sent);
            } else {
                idle(node);
            }
        }
    }

    private void idle(RaftNode node) {
        lock.lock();
        try {
            if (running && next > node.lastIndex() && !heartbeatRequested) {
                signal.awaitNanos(HEARTBEAT_NANOS);
            }
            heartbeatRequested = false;
        } catch (InterruptedException interrupted) {
            running = false;
        } finally {
            lock.unlock();
        }
    }

    private static void closeQuietly(Socket connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (IOException ignored) {
                // já está fechada
            }
        }
    }

    private static void join(Thread reader) {
        try {
            reader.join(2_000);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
