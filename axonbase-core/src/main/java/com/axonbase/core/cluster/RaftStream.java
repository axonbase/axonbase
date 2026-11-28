package com.axonbase.core.cluster;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Corpo de um AppendEntries com várias entradas: termo da entrada anterior, quantidade e, para cada uma,
 * termo, tamanho e os bytes do lote exatamente como estão no log.
 */
final class RaftStream {

    private RaftStream() {
    }

    record Entry(long term, byte[] payload) {
    }

    record Batch(long previousTerm, List<Entry> entries) {
    }

    static byte[] encode(long previousTerm, List<Entry> entries) {
        int size = 12;
        for (Entry entry : entries) {
            size += 12 + entry.payload().length;
        }
        try (var bytes = new ByteArrayOutputStream(size); var out = new DataOutputStream(bytes)) {
            out.writeLong(previousTerm);
            out.writeInt(entries.size());
            for (Entry entry : entries) {
                out.writeLong(entry.term());
                out.writeInt(entry.payload().length);
                out.write(entry.payload());
            }
            out.flush();
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Batch decode(byte[] raw) {
        try (var in = new DataInputStream(new ByteArrayInputStream(raw))) {
            long previousTerm = in.readLong();
            int count = in.readInt();
            if (count < 0 || count > raw.length / 12) {
                throw new IllegalArgumentException("contagem de entradas inválida: " + count);
            }
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                long term = in.readLong();
                int length = in.readInt();
                if (length < 0 || length > in.available()) {
                    throw new IllegalArgumentException("tamanho de entrada inválido: " + length);
                }
                entries.add(new Entry(term, in.readNBytes(length)));
            }
            return new Batch(previousTerm, entries);
        } catch (IOException e) {
            throw new IllegalArgumentException("AppendEntries truncado", e);
        }
    }
}
