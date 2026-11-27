package com.axonbase.core.partition;

import com.axonbase.common.Messages;
import com.axonbase.core.catalog.Database;
import com.axonbase.core.partition.cold.ColdFailure;
import com.axonbase.core.storage.KvBackend;

import java.util.Optional;
import java.util.function.BiPredicate;

/** Leitura dos registros de uma partição que está na camada fria. */
public interface ColdRecords {

    /** O registro de chave {@code recordKey}, se a partição o tiver. */
    Optional<byte[]> get(Database db, KvBackend kv, String table, PartitionDescriptor partition, String recordKey);

    /**
     * Visita, em ordem de chave, os registros cuja chave começa com {@code keyPrefix}.
     *
     * @return {@code false} quando o visitante pediu para parar
     */
    boolean scan(Database db, String table, PartitionDescriptor partition, String keyPrefix,
                 BiPredicate<String, byte[]> visitor);

    /** Sem camada fria configurada: ler uma partição fria é um erro, nunca um resultado incompleto. */
    ColdRecords UNCONFIGURED = new ColdRecords() {
        @Override
        public Optional<byte[]> get(Database db, KvBackend kv, String table, PartitionDescriptor partition,
                                    String recordKey) {
            throw ColdFailure.error(Messages.get("cold_not_configured"));
        }

        @Override
        public boolean scan(Database db, String table, PartitionDescriptor partition, String keyPrefix,
                            BiPredicate<String, byte[]> visitor) {
            throw ColdFailure.error(Messages.get("cold_not_configured"));
        }
    };
}
