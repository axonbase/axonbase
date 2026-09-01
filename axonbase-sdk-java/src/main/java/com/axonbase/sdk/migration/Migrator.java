package com.axonbase.sdk.migration;

import com.axonbase.common.Messages;
import com.axonbase.sdk.Axon;
import com.axonbase.value.AxonValue;

import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.function.BiConsumer;
import java.util.stream.Stream;

public class Migrator {

    private final Axon axon;
    private BiConsumer<String, String> beforeHook = (v, n) -> {};
    private BiConsumer<String, String> afterHook = (v, n) -> {};

    public Migrator(Axon axon) {
        this.axon = axon;
    }

    public Migrator onBefore(BiConsumer<String, String> hook) {
        this.beforeHook = hook;
        return this;
    }

    public Migrator onAfter(BiConsumer<String, String> hook) {
        this.afterHook = hook;
        return this;
    }

    public void ensureTable() {
        axon.query("DEFINE TABLE _migration SCHEMAFULL;"
            + "DEFINE FIELD version ON TABLE _migration TYPE string;"
            + "DEFINE FIELD applied_at ON TABLE _migration TYPE datetime;"
            + "DEFINE FIELD checksum ON TABLE _migration TYPE string;"
            + "DEFINE FIELD name ON TABLE _migration TYPE string;");
    }

    public Map<String, String> applied() {
        Map<String, String> result = new LinkedHashMap<>();
        try {
            AxonValue rows = axon.query("SELECT version, checksum FROM _migration ORDER BY version ASC;");
            if (rows.isArray()) {
                for (AxonValue row : rows.asArray()) {
                    if (row.isObject()) {
                        var obj = row.asObject();
                        String v = obj.containsKey("version") ? obj.get("version").asString() : null;
                        String c = obj.containsKey("checksum") ? obj.get("checksum").asString() : null;
                        if (v != null && c != null) result.put(v, c);
                    }
                }
            }
        } catch (Exception ignored) {}
        return result;
    }

    public List<Migration> load(String path) throws Exception {
        Path dir = Paths.get(path);
        if (!Files.isDirectory(dir)) {
            throw new IllegalArgumentException(Messages.get("sdk_migration_directory_not_found", path));
        }
        List<Migration> migrations = new ArrayList<>();
        try (Stream<Path> files = Files.list(dir)) {
            files.filter(f -> f.toString().endsWith(".axql"))
                 .sorted()
                 .forEach(f -> {
                     String name = f.getFileName().toString();
                     String version = name.contains("_") ? name.substring(0, name.indexOf('_')) : name.replace(".axql", "");
                     try {
                         String sql = Files.readString(f).trim();
                         String checksum = sha256(Files.readAllBytes(f));
                         migrations.add(new Migration(version, name, sql, checksum));
                     } catch (Exception e) {
                         throw new RuntimeException(Messages.get("sdk_migration_read_failed", name), e);
                     }
                 });
        }
        return migrations;
    }

    public List<Migration> up(String path) throws Exception {
        List<Migration> all = load(path);
        Map<String, String> applied = applied();
        List<Migration> pending = new ArrayList<>();

        for (Migration m : all) {
            if (applied.containsKey(m.version())) {
                if (!applied.get(m.version()).equals(m.checksum())) {
                    throw new RuntimeException(Messages.get("sdk_migration_checksum_changed", m.name()));
                }
                continue;
            }
            pending.add(m);
        }

        if (pending.isEmpty()) return List.of();

        ensureTable();
        for (Migration m : pending) {
            beforeHook.accept(m.version(), m.name());
            applyOne(m);
            afterHook.accept(m.version(), m.name());
        }
        return pending;
    }

    public List<StatusEntry> status(String path) throws Exception {
        List<Migration> all = load(path);
        Map<String, String> applied = applied();
        List<StatusEntry> result = new ArrayList<>();
        for (Migration m : all) {
            result.add(new StatusEntry(m, applied.containsKey(m.version())));
        }
        return result;
    }

    private void applyOne(Migration m) {
        if (m.sql() == null || m.sql().isBlank()) return;
        axon.query(m.sql());
        String rid = "v_" + m.version();
        axon.query("UPSERT _migration:" + rid
            + " CONTENT {version: \"" + esc(m.version()) + "\", name: \"" + esc(m.name())
            + "\", checksum: \"" + esc(m.checksum()) + "\", applied_at: time::now()}");
    }

    private static String esc(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String sha256(byte[] data) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(data);
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public record Migration(String version, String name, String sql, String checksum) {}
    public record StatusEntry(Migration migration, boolean applied) {}
}
