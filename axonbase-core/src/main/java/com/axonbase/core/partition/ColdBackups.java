package com.axonbase.core.partition;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.core.engine.Datastore;
import com.axonbase.core.partition.cold.ColdHolds;
import com.axonbase.core.storage.PhysicalBackups;
import com.axonbase.core.storage.RocksDbBackend;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Backups com camada fria: nenhum copia objetos. Cada um bloqueia os objetos que referencia, e um restore só troca a
 * base se todos eles existirem. Sem conseguir bloquear, o backup falha.
 */
public final class ColdBackups {

    private static final String HOLDER_FILE = "axon-cold-holder";
    private static final Duration PENDING = Duration.ofDays(1);

    private ColdBackups() {
    }

    /** Um bloqueio em formação: tomado antes de o backup ler a base e completado depois. */
    public static final class Hold {
        private final Datastore ds;
        private final String name;
        private final long expiresAt;
        private final Set<String> objects = new TreeSet<>();

        private Hold(Datastore ds, String name, long expiresAt) {
            this.ds = ds;
            this.name = name;
            this.expiresAt = expiresAt;
            add();
        }

        /** Bloqueia também o que passou a ser citado enquanto o backup lia a base. */
        public void complete() {
            add();
        }

        private void add() {
            Set<String> referenced = ColdGarbage.referenced(ds);
            if (!referenced.isEmpty()) {
                objects.addAll(referenced);
                put(ds, name, objects, expiresAt);
            }
        }
    }

    /** O bloqueio de um backup lógico, que o motor não sabe quando é apagado: vale pelo prazo configurado. */
    public static Hold beginLogical(Datastore ds) {
        long expiresAt = System.currentTimeMillis() + ds.coldTier().settings().logicalHold().toMillis();
        return new Hold(ds, "logical-" + UUID.randomUUID(), expiresAt);
    }

    /** Cria um backup físico segurando, sem prazo, os objetos frios que ele referencia. */
    public static PhysicalBackups.Info createPhysical(Datastore ds, PhysicalBackups backups, RocksDbBackend rocks,
                                                      Path backupDir) {
        Hold pending = new Hold(ds, "pending-" + UUID.randomUUID(),
            System.currentTimeMillis() + PENDING.toMillis());
        PhysicalBackups.Info info = backups.create(rocks);
        pending.complete();
        if (!pending.objects.isEmpty()) {
            try {
                put(ds, physicalPrefix(backupDir) + info.id(), pending.objects, ColdHolds.NEVER);
            } catch (AxonError e) {
                backups.delete(info.id()); // um backup sem bloqueio poderia perder os objetos depois
                throw e;
            }
            release(ds, pending.name);
        }
        return info;
    }

    /**
     * Depois da retenção local: o bloqueio de um backup apagado passa a ter prazo, porque uma cópia dele pode estar
     * guardada fora do servidor.
     */
    public static void releasePurged(Datastore ds, Path backupDir, List<PhysicalBackups.Info> remaining) {
        if (!ds.coldTier().configured()) {
            return;
        }
        String prefix = physicalPrefix(backupDir);
        Set<String> kept = remaining.stream().map(i -> prefix + i.id()).collect(Collectors.toSet());
        long expiresAt = System.currentTimeMillis() + ds.coldTier().settings().logicalHold().toMillis();
        try {
            ColdHolds holds = ds.coldTier().holds();
            for (String name : holds.names(prefix)) {
                if (!kept.contains(name)) {
                    // outro nome: um backup futuro não reaproveita o bloqueio com prazo
                    holds.put("retired-" + name + "-" + UUID.randomUUID(), holds.objects(name), expiresAt);
                    holds.release(name);
                }
            }
        } catch (IOException e) {
            throw AxonError.internal(Messages.get("cold_hold_failed", e.getMessage()));
        }
    }

    /** Confere que os objetos frios citados por uma base preparada para restore existem. */
    public static void requireRestorable(Datastore ds, Path preparedBase) {
        Set<String> objects = new TreeSet<>();
        try (RocksDbBackend base = new RocksDbBackend(preparedBase.toString(), RocksDbBackend.SyncMode.OFF)) {
            base.scanEntries(PartitionKeys.DESCRIPTORS, (key, value) -> {
                PartitionDescriptor partition = PartitionDescriptor.decode(value);
                if (partition.coldData()) {
                    objects.add(partition.object());
                }
                return true;
            });
        }
        requireObjects(ds, objects);
    }

    /** @throws AxonError quando algum objeto falta ou a camada fria não está configurada */
    public static void requireObjects(Datastore ds, Collection<String> objects) {
        if (objects.isEmpty()) {
            return;
        }
        List<String> missing = new ArrayList<>();
        try {
            for (String object : objects) {
                if (!ds.coldTier().store().exists(object)) {
                    missing.add(object);
                }
            }
        } catch (IOException e) {
            throw AxonError.internal(Messages.get("cold_unavailable", "restore", e.getMessage()));
        }
        if (!missing.isEmpty()) {
            throw AxonError.internal(Messages.get("cold_missing", String.join(", ", missing)));
        }
    }

    private static void put(Datastore ds, String name, Set<String> objects, long expiresAt) {
        ColdHolds holds = ds.coldTier().holds(); // sem camada fria configurada, falha aqui
        try {
            holds.put(name, objects, expiresAt);
        } catch (IOException | RuntimeException e) {
            throw AxonError.internal(Messages.get("cold_hold_failed", e.getMessage()));
        }
    }

    private static void release(Datastore ds, String name) {
        try {
            ds.coldTier().holds().release(name);
        } catch (IOException | RuntimeException e) {
            // o bloqueio provisório vence sozinho em um dia
            System.err.println("{\"event\":\"cold_pending_hold_not_released\",\"hold\":\"" + name + "\"}");
        }
    }

    /** O começo do nome dos bloqueios de um diretório de backup, identificado por um arquivo dentro dele. */
    static String physicalPrefix(Path backupDir) {
        Path file = backupDir.resolve(HOLDER_FILE);
        try {
            if (!Files.exists(file)) {
                Files.createDirectories(backupDir);
                try {
                    Files.writeString(file, UUID.randomUUID().toString(), StandardOpenOption.CREATE_NEW);
                } catch (FileAlreadyExistsException raced) {
                    // outro criou primeiro: vale o dele
                }
            }
            return "physical-" + Files.readString(file, StandardCharsets.UTF_8).trim() + "-";
        } catch (IOException e) {
            throw AxonError.internal(Messages.get("cold_hold_failed", e.getMessage()));
        }
    }
}
