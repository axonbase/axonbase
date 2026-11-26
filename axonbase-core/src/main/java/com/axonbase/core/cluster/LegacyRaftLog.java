package com.axonbase.core.cluster;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Leitor do log em arquivo único ({@code raft.log}) das versões até a 0.10.x, usado só na migração. */
final class LegacyRaftLog {

    private LegacyRaftLog() {
    }

    @FunctionalInterface
    interface Visitor {
        void accept(FileRaftLog.Entry entry) throws IOException;
    }

    /** Entrega cada entrada completa, na ordem; para na primeira incompleta (cauda cortada por uma queda). */
    static void read(Path file, Visitor visitor) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file));
        while (buffer.remaining() >= 16) {
            FileRaftLog.Entry entry;
            try {
                long index = buffer.getLong();
                long term = buffer.getLong();
                entry = new FileRaftLog.Entry(index, term, batch(buffer));
            } catch (BufferUnderflowException torn) {
                return;
            }
            visitor.accept(entry);
        }
    }

    private static CommittedBatch batch(ByteBuffer buffer) {
        String transactionId = shortString(buffer);
        int deletes = count(buffer);
        Set<String> removed = new LinkedHashSet<>();
        for (int i = 0; i < deletes; i++) {
            removed.add(shortString(buffer));
        }
        int puts = count(buffer);
        Map<String, byte[]> written = new LinkedHashMap<>();
        for (int i = 0; i < puts; i++) {
            String key = shortString(buffer);
            written.put(key, bytes(buffer, buffer.getInt()));
        }
        return new CommittedBatch(transactionId, written, removed);
    }

    private static int count(ByteBuffer buffer) {
        int count = buffer.getInt();
        if (count < 0 || count > buffer.remaining()) {
            throw new BufferUnderflowException();
        }
        return count;
    }

    private static String shortString(ByteBuffer buffer) {
        return new String(bytes(buffer, buffer.getShort()), StandardCharsets.UTF_8);
    }

    /** Só aloca depois de saber que os bytes existem. */
    private static byte[] bytes(ByteBuffer buffer, int length) {
        if (length < 0 || length > buffer.remaining()) {
            throw new BufferUnderflowException();
        }
        byte[] raw = new byte[length];
        buffer.get(raw);
        return raw;
    }
}
