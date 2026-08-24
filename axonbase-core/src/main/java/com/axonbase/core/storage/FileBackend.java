package com.axonbase.core.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Backend persistente en ficheiro, sinxelo: garda un snapshot JSON do mapa en
 * disco. Non inclúe WAL (Write-Ahead Logging) nin MVCC; é unha opción lixeira
 * para o MVP que demostra a interface {@link KvBackend}. A chave ordénase
 * lexicograficamente (orde do {@link TreeMap}).
 */
public final class FileBackend implements KvBackend {

    private final TreeMap<String, byte[]> data = new TreeMap<>();
    private final String persistedPath;
    private boolean dirty;

    public FileBackend(String path) {
        this.persistedPath = path;
        load();
    }

    private void load() {
        try (var in = java.nio.file.Files.newInputStream(java.nio.file.Path.of(persistedPath))) {
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            var parsed = com.axonbase.value.AxonJson.parseDocument(json);
            if (parsed.isObject()) {
                for (var e : parsed.asObject().entrySet()) {
                    data.put(e.getKey(), com.axonbase.value.AxonJson.write(e.getValue()).getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (IOException ex) {
            // ficheiro aínda non existe; mapa baleiro
        }
    }

    private void flushImpl() {
        if (!dirty) {
            return;
        }
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var e : data.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(escape(e.getKey())).append('"').append(':')
                .append(new String(e.getValue(), StandardCharsets.UTF_8));
        }
        sb.append('}');
        try {
            java.nio.file.Files.createDirectories(java.nio.file.Path.of(persistedPath).getParent());
            java.nio.file.Files.writeString(java.nio.file.Path.of(persistedPath), sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        dirty = false;
    }

    @Override
    public Optional<byte[]> get(String key) {
        byte[] v = data.get(key);
        return v == null ? Optional.empty() : Optional.of(v);
    }

    @Override
    public void put(String key, byte[] value) {
        data.put(key, value);
        dirty = true;
    }

    @Override
    public boolean putIfAbsent(String key, byte[] value) {
        if (data.putIfAbsent(key, value) == null) {
            dirty = true;
            return true;
        }
        return false;
    }

    @Override
    public boolean delete(String key) {
        if (data.remove(key) != null) {
            dirty = true;
            return true;
        }
        return false;
    }

    @Override
    public List<String> keysWithPrefix(String prefix) {
        var out = new java.util.ArrayList<String>();
        for (String k : data.tailMap(prefix).keySet()) {
            if (k.startsWith(prefix)) {
                out.add(k);
            } else {
                break;
            }
        }
        return out;
    }

    @Override
    public void flush() {
        flushImpl();
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}