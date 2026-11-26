package com.axonbase.core.cluster;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Estado Raft durável de um nó: termo, voto, último índice aplicado e peers.
 *
 * <p>Termo e voto são sincronizados em disco antes de qualquer resposta, em todos os modos de
 * {@code AXON_STORAGE_SYNC}: esquecer um voto permitiria dois líderes no mesmo termo.</p>
 */
public final class RaftNodeState {
    private final Path file;
    private final Path appliedFile;
    private volatile long term;
    private String votedFor = "";
    private long appliedIndex;
    private long appliedTerm;
    private List<InetSocketAddress> peers = new ArrayList<>();

    public RaftNodeState(Path directory) {
        try {
            Files.createDirectories(directory);
            file = directory.resolve("raft.state");
            appliedFile = directory.resolve("raft.applied");
            load();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized long term() {
        return term;
    }

    /** Leitura sem trava, para quem não pode esperar uma gravação do estado em curso. */
    long currentTerm() {
        return term;
    }

    public synchronized String votedFor() {
        return votedFor;
    }

    public synchronized long appliedIndex() {
        return appliedIndex;
    }

    synchronized long appliedTerm() {
        return appliedTerm;
    }

    public synchronized List<InetSocketAddress> peers() {
        return List.copyOf(peers);
    }

    public synchronized void update(long term, String votedFor, long appliedIndex) {
        long previousTerm = this.term;
        String previousVote = this.votedFor;
        long previousApplied = this.appliedIndex;
        this.term = term;
        this.votedFor = votedFor == null ? "" : votedFor;
        this.appliedIndex = appliedIndex;
        try {
            save();
        } catch (RuntimeException notPersisted) {
            // Um voto que não chegou ao disco não pode valer nem em memória.
            this.term = previousTerm;
            this.votedFor = previousVote;
            this.appliedIndex = previousApplied;
            throw notPersisted;
        }
    }

    /** Grava termo e voto sem tocar no índice aplicado. */
    synchronized void vote(long term, String votedFor) {
        update(term, votedFor, appliedIndex);
    }

    /**
     * Registra até onde o armazenamento aplicou o log. Sem {@code durable} (armazenamento volátil, a cada
     * aplicação) vai para um arquivo próprio, sem fsync, para nunca arriscar o termo e o voto.
     */
    synchronized void checkpoint(long index, long indexTerm, boolean durable) {
        this.appliedIndex = index;
        this.appliedTerm = indexTerm;
        if (durable) {
            save();
            return;
        }
        try {
            Path tmp = appliedFile.resolveSibling("raft.applied.tmp");
            Files.writeString(tmp, index + " " + indexTerm);
            Files.move(tmp, appliedFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void updatePeers(List<InetSocketAddress> peers) {
        this.peers = peers == null ? new ArrayList<>() : new ArrayList<>(peers);
        save();
    }

    private void load() throws IOException {
        if (!Files.exists(file)) {
            loadApplied();
            return;
        }
        Properties p = new Properties();
        try (var in = Files.newInputStream(file)) {
            p.load(in);
        }
        term = Long.parseLong(p.getProperty("term", "0"));
        votedFor = p.getProperty("voted_for", "");
        appliedIndex = Long.parseLong(p.getProperty("applied_index", "0"));
        appliedTerm = Long.parseLong(p.getProperty("applied_term", "0"));
        peers = parsePeers(p.getProperty("peers", ""));
        loadApplied();
    }

    private void loadApplied() throws IOException {
        if (!Files.exists(appliedFile)) {
            return;
        }
        String[] parts = Files.readString(appliedFile).trim().split(" ");
        if (parts.length == 2) {
            appliedIndex = Long.parseLong(parts[0]);
            appliedTerm = Long.parseLong(parts[1]);
        }
    }

    private void save() {
        Properties p = new Properties();
        p.setProperty("term", Long.toString(term));
        p.setProperty("voted_for", votedFor);
        p.setProperty("applied_index", Long.toString(appliedIndex));
        p.setProperty("applied_term", Long.toString(appliedTerm));
        p.setProperty("peers", formatPeers(peers));
        try {
            var bytes = new ByteArrayOutputStream();
            p.store(bytes, "AxonBase Raft state");
            LogSegment.writeAtomically(file, bytes.toByteArray());
            // O estado completo é mais novo que o ponto volátil.
            Files.deleteIfExists(appliedFile);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static List<InetSocketAddress> parsePeers(String raw) {
        List<InetSocketAddress> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        for (String part : raw.split(",")) {
            part = part.trim();
            int i = part.lastIndexOf(':');
            if (i < 1) {
                continue;
            }
            try {
                out.add(new InetSocketAddress(part.substring(0, i), Integer.parseInt(part.substring(i + 1))));
            } catch (NumberFormatException ignored) {
                // endereço inválido é descartado
            }
        }
        return out;
    }

    static String formatPeers(List<InetSocketAddress> peers) {
        StringBuilder sb = new StringBuilder();
        for (InetSocketAddress p : peers) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p.getHostString()).append(':').append(p.getPort());
        }
        return sb.toString();
    }
}
