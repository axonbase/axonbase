package com.axonbase.core.partition;

import com.axonbase.core.Session;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.catalog.TtlSpec;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.partition.cold.BloomFilter;
import com.axonbase.core.partition.cold.ColdObject;
import com.axonbase.core.storage.RangeDelete;
import com.axonbase.core.storage.Transaction;
import com.axonbase.value.AxonJson;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Leva uma partição para a camada fria e a traz de volta. Cada passo é um commit comum, e uma queda no meio deixa a
 * partição num estado que a rodada seguinte retoma, com os dados locais intactos.
 *
 * <p>Toda decisão relê o descritor registrando a leitura: se alguém o mudar até o commit, nada é gravado. Nenhum
 * objeto é apagado aqui, porque outro nó (ou uma base restaurada) pode estar usando o mesmo armazenamento: o que
 * sobra fica para a coleta de lixo.</p>
 *
 * <ol>
 *   <li>congelar: o estado vira {@code FREEZING}, com um nome de objeto novo a cada tentativa, e nenhuma escrita entra
 *   mais;</li>
 *   <li>enviar: grava o objeto, baixa de volta e compara, sem segurar trava alguma;</li>
 *   <li>trocar: num lote só, apaga os registros locais por faixa e passa o descritor a {@code COLD}.</li>
 * </ol>
 */
final class ColdMover {

    private final Datastore ds;
    private final BooleanSupplier stopping;

    /** @param stopping {@code true} quando o motor está desligando: nada mais é gravado */
    ColdMover(Datastore ds, BooleanSupplier stopping) {
        this.ds = ds;
        this.stopping = stopping;
    }

    /** @return {@code true} quando a partição terminou na camada fria */
    boolean freeze(Database db, String table, long start) {
        Optional<PartitionDescriptor> frozen = reserve(db, table, start);
        if (frozen.isEmpty()) {
            return false;
        }
        String key = frozen.get().object();
        try {
            Upload upload = upload(db, table, start, key);
            if (stopping.getAsBoolean()) {
                return false;
            }
            return change(db, table, start, tx -> {
                PartitionDescriptor current = PartitionStore.track(db, tx, table, start).orElse(null);
                if (current == null || current.state() != PartitionDescriptor.State.FREEZING
                    || !key.equals(current.object())) {
                    return false; // alguém desistiu ou removeu a partição durante o envio
                }
                String prefix = PartitionKeys.partitionPrefix(db.ns(), db.db(), table, start);
                // a faixa apagada precisa conter exatamente o que foi enviado: com outra linha ali, nada sai, e a
                // rodada seguinte envia de novo
                if (!holdsOnly(db, prefix, upload.recordKeys())) {
                    System.err.println("{\"event\":\"partition_freeze_mismatch\",\"target\":\"" + db.ns() + "."
                        + db.db() + "." + table + " " + start + "\"}");
                    return false;
                }
                PartitionChanges changes = new PartitionChanges();
                // só os registros (n, s, x) saem; os índices locais, que vêm antes na ordem, ficam
                changes.deleteRange(new RangeDelete.Range(prefix + "n", prefix + "y"));
                changes.save(db, table, current.cold(key, upload.rows(), upload.bytes()));
                changes.put(PartitionKeys.bloomKey(db.ns(), db.db(), table, start), upload.filter().encode());
                changes.apply(db, table, tx);
                return true;
            });
        } catch (TooLarge refused) {
            keepLocal(db, table, start, key);
            logTooLarge(db, table, start);
            return false;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Em cluster, uma partição que não conseguiria voltar não vai: ela fica local, fixada, e volta a aceitar escrita. */
    private void keepLocal(Database db, String table, long start, String key) {
        change(db, table, start, tx -> {
            PartitionDescriptor current = PartitionStore.track(db, tx, table, start).orElse(null);
            if (current == null || current.state() != PartitionDescriptor.State.FREEZING
                || !key.equals(current.object())) {
                return false;
            }
            PartitionChanges changes = new PartitionChanges();
            changes.save(db, table, current.requestHot());
            changes.apply(db, table, tx);
            return true;
        });
    }

    private void logTooLarge(Database db, String table, long start) {
        System.err.println("{\"event\":\"partition_too_large_for_cluster\",\"target\":\"" + db.ns() + "."
            + db.db() + "." + table + "\",\"error\":\"" + ds.coldTier().tooLargeForCluster(table, start) + "\"}");
    }

    /** A partição passa do teto de uma entrada do log Raft. */
    private static final class TooLarge extends IOException {
    }

    /** @return {@code true} quando a partição voltou para o armazenamento local */
    boolean thaw(Database db, String table, long start) {
        PartitionDescriptor thawing = PartitionStore.read(db, db.records(), table, start).orElse(null);
        if (thawing == null || thawing.state() != PartitionDescriptor.State.THAWING) {
            return false;
        }
        if (ds.clustered()
            && !ds.coldTier().fitsClusterEntry(db, table, thawing.rows(), thawing.bytes())) {
            // uma volta pedida antes (ou antes de o nó entrar no cluster) fica lida do objeto; TO COLD a encerra
            logTooLarge(db, table, start);
            return false;
        }
        ColdObject.Reader reader = ds.coldTier().cache().open(thawing.object());
        if (stopping.getAsBoolean()) {
            return false;
        }
        TtlSpec ttl = db.catalog().ttl(table);
        return change(db, table, start, tx -> {
            PartitionDescriptor current = PartitionStore.track(db, tx, table, start).orElse(null);
            if (current == null || current.state() != PartitionDescriptor.State.THAWING
                || !thawing.object().equals(current.object())) {
                return false;
            }
            String prefix = PartitionKeys.partitionPrefix(db.ns(), db.db(), table, start);
            reader.scan("", (recordKey, value) -> {
                tx.putBlind(prefix + recordKey, value);
                // um TTL definido enquanto a partição estava fria ainda não conhece estas linhas
                if (ttl != null) {
                    PartitionedKv.expiryOf(ttl, AxonJson.decode(value)).ifPresent(expiry -> tx.putBlind(
                        PartitionKeys.ttlKey(db.ns(), db.db(), table, expiry, recordKey), new byte[0]));
                }
                return true;
            });
            PartitionChanges changes = new PartitionChanges();
            changes.save(db, table, current.thawed());
            changes.delete(PartitionKeys.bloomKey(db.ns(), db.db(), table, start));
            changes.apply(db, table, tx);
            return true;
        });
    }

    /** Deixa a partição {@code FREEZING} com um nome de objeto novo, ou vazio se ela não está para congelar. */
    private Optional<PartitionDescriptor> reserve(Database db, String table, long start) {
        PartitionDescriptor now = PartitionStore.read(db, db.records(), table, start).orElse(null);
        if (now == null || now.coldData()) {
            return Optional.empty();
        }
        String key = "parts/" + db.ns() + "/" + db.db() + "/" + table + "/" + PartitionKeys.pad(start) + "-"
            + UUID.randomUUID() + ".axc";
        PartitionDescriptor reserved = now.freezing(key);
        boolean saved = change(db, table, start, tx -> {
            PartitionDescriptor current = PartitionStore.track(db, tx, table, start).orElse(null);
            if (!now.equals(current)) {
                return false;
            }
            PartitionChanges changes = new PartitionChanges();
            changes.save(db, table, reserved);
            changes.apply(db, table, tx);
            return true;
        });
        return saved ? Optional.of(reserved) : Optional.empty();
    }

    private record Upload(long rows, long bytes, BloomFilter filter, Set<String> recordKeys) {
    }

    /** {@code true} quando os registros locais da partição são exatamente os de {@code sent}. */
    private static boolean holdsOnly(Database db, String prefix, Set<String> sent) {
        int[] found = {0};
        boolean[] unknown = {false};
        for (String lead : List.of("n", "s", "x")) {
            db.records().scanPrefix(prefix + lead, physical -> {
                found[0]++;
                unknown[0] = !sent.contains(physical.substring(prefix.length()));
                return !unknown[0];
            });
            if (unknown[0]) {
                return false;
            }
        }
        return found[0] == sent.size();
    }

    private Upload upload(Database db, String table, long start, String key) throws IOException {
        String prefix = PartitionKeys.partitionPrefix(db.ns(), db.db(), table, start);
        List<byte[]> recordKeys = new ArrayList<>();
        for (String lead : List.of("n", "s", "x")) {
            db.records().scanPrefix(prefix + lead, physical -> {
                recordKeys.add(physical.substring(prefix.length()).getBytes(StandardCharsets.UTF_8));
                return true;
            });
        }
        recordKeys.sort(Arrays::compareUnsigned); // o objeto segue a ordem dos bytes
        BloomFilter filter = BloomFilter.sizedFor(recordKeys.size());
        Set<String> sent = new HashSet<>();
        Path written = Files.createTempFile("axon-freeze-", ".gz");
        Path readBack = Files.createTempFile("axon-freeze-check-", ".gz");
        try {
            long rows;
            long bytes;
            try (ColdObject.Writer writer = ColdObject.writer(written)) {
                for (byte[] raw : recordKeys) {
                    String recordKey = new String(raw, StandardCharsets.UTF_8);
                    byte[] value = db.records().get(prefix + recordKey).orElseThrow(
                        () -> new IOException("registro sumiu de uma partição congelada: " + recordKey));
                    writer.add(recordKey, value);
                    filter.add(recordKey);
                    sent.add(recordKey);
                }
                rows = writer.rows();
                bytes = writer.bytes();
                if (bytes > Integer.MAX_VALUE - (1L << 20)) {
                    throw new IOException("a partição passa de 2 GB e não cabe num objeto frio: "
                        + "use um INTERVAL menor");
                }
                if (ds.clustered() && !ds.coldTier().fitsClusterEntry(db, table, rows, bytes)) {
                    throw new TooLarge();
                }
                writer.finish();
            }
            ds.coldTier().store().upload(key, written);
            if (!ds.coldTier().store().download(key, readBack) || Files.mismatch(written, readBack) != -1L) {
                throw new IOException("o objeto enviado não confere: " + key);
            }
            return new Upload(rows, bytes, filter, sent);
        } finally {
            Files.deleteIfExists(written);
            Files.deleteIfExists(readBack);
        }
    }

    /** Uma mudança de partição numa transação própria. */
    private boolean change(Database db, String table, long start, Function<Transaction, Boolean> work) {
        Session session = new Session(db.ns(), db.db());
        ds.beginSession(session);
        try {
            boolean changed = work.apply(session.tx());
            if (changed) {
                ds.commitSession(session);
            }
            return changed;
        } finally {
            if (session.inTransaction()) {
                ds.cancelSession(session);
            }
        }
    }
}
