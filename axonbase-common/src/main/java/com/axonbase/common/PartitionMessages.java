package com.axonbase.common;

import java.util.HashMap;
import java.util.Map;

/** Mensagens de partição por tempo, TTL e camada fria, juntadas ao catálogo de {@link Messages}. */
final class PartitionMessages {

    static final Map<String, String> PT_BR = Map.ofEntries(
        Map.entry("partition_changed", "a partição %s mudou ou foi removida durante a transação"),
        Map.entry("partition_readonly", "a partição %s está no estado %s e não aceita escrita"),
        Map.entry("partition_field_required",
            "a tabela %s é particionada: o campo %s precisa ser um datetime a partir de 1970"),
        Map.entry("partition_limit", "a tabela %s chegou ao limite de %s partições"),
        Map.entry("partition_requires_empty", "só é possível particionar uma tabela vazia: %s tem registros"),
        Map.entry("partition_index_unsupported",
            "o índice %s não é aceito em tabela particionada: use UNIQUE ou COLUMNAR"),
        Map.entry("partition_not_partitioned", "a tabela %s não é particionada"),
        Map.entry("partition_interval_invalid", "INTERVAL precisa ser de pelo menos 1 segundo"),
        Map.entry("partition_cold_after_invalid", "COLD AFTER precisa ser menor que RETENTION"),
        Map.entry("partition_redefine", "a tabela %s já tem partições: campo e intervalo não podem mudar"),
        Map.entry("partition_in_write", "comandos de partição não podem rodar dentro de uma escrita (evento ou bloco)"),
        Map.entry("partition_none_found", "nenhuma partição de %s cobre o instante %s"),
        Map.entry("partition_time_invalid", "o instante precisa ser um datetime: %s"),
        Map.entry("partition_index_orphan", "índice sem registro na tabela particionada %s: %s"),
        Map.entry("ttl_field_invalid", "TTL precisa de um campo e de um AFTER não negativo"),
        Map.entry("ttl_not_defined", "a tabela %s não tem TTL"),
        Map.entry("cold_not_configured",
            "a camada fria não está configurada: defina AXON_COLD_DIR ou AXON_COLD_S3"),
        Map.entry("cold_unavailable", "a camada fria não respondeu ao ler %s: %s"),
        Map.entry("cold_corrupted", "o objeto frio %s está corrompido ou incompleto"),
        Map.entry("cold_missing", "faltam objetos frios referenciados pela base: %s"),
        Map.entry("cold_hold_failed", "não foi possível bloquear os objetos frios do backup: %s")
    );

    static final Map<String, String> EN = Map.ofEntries(
        Map.entry("partition_changed", "partition %s changed or was dropped during the transaction"),
        Map.entry("partition_readonly", "partition %s is %s and does not accept writes"),
        Map.entry("partition_field_required",
            "table %s is partitioned: field %s must be a datetime from 1970 on"),
        Map.entry("partition_limit", "table %s reached the limit of %s partitions"),
        Map.entry("partition_requires_empty", "only an empty table can be partitioned: %s has records"),
        Map.entry("partition_index_unsupported",
            "index %s is not supported on a partitioned table: use UNIQUE or COLUMNAR"),
        Map.entry("partition_not_partitioned", "table %s is not partitioned"),
        Map.entry("partition_interval_invalid", "INTERVAL must be at least 1 second"),
        Map.entry("partition_cold_after_invalid", "COLD AFTER must be shorter than RETENTION"),
        Map.entry("partition_redefine", "table %s already has partitions: field and interval cannot change"),
        Map.entry("partition_in_write", "partition commands cannot run inside a write (event or block)"),
        Map.entry("partition_none_found", "no partition of %s covers instant %s"),
        Map.entry("partition_time_invalid", "the instant must be a datetime: %s"),
        Map.entry("partition_index_orphan", "index entry without a record in partitioned table %s: %s"),
        Map.entry("ttl_field_invalid", "TTL needs a field and a non negative AFTER"),
        Map.entry("ttl_not_defined", "table %s has no TTL"),
        Map.entry("cold_not_configured", "the cold tier is not configured: set AXON_COLD_DIR or AXON_COLD_S3"),
        Map.entry("cold_unavailable", "the cold tier did not answer while reading %s: %s"),
        Map.entry("cold_corrupted", "cold object %s is corrupted or incomplete"),
        Map.entry("cold_missing", "cold objects referenced by the database are missing: %s"),
        Map.entry("cold_hold_failed", "could not hold the backup's cold objects: %s")
    );

    private PartitionMessages() {
    }

    static Map<String, String> addTo(Map<String, String> base, Map<String, String> extra) {
        Map<String, String> merged = new HashMap<>(base);
        merged.putAll(extra);
        return Map.copyOf(merged);
    }
}
