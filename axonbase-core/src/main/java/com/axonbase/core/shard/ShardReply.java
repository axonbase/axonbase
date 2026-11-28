package com.axonbase.core.shard;

import com.axonbase.core.hlc.HlcTimestamp;
import com.axonbase.value.AxonValue;

/**
 * Resposta de um shard.
 *
 * @param value resultado da última instrução
 * @param stamp relógio híbrido do shard depois de executar: maior que o de qualquer escrita feita pela requisição
 */
public record ShardReply(AxonValue value, HlcTimestamp stamp) {
}
