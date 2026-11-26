package com.axonbase.core.shard;

import com.axonbase.common.Messages;

import java.util.Map;

/**
 * Mensagens do roteamento entre shards, nos dois idiomas do {@link Messages}. Ficam aqui para o pacote não crescer o
 * catálogo comum; o idioma é o mesmo dele.
 */
final class ShardMessages {

    private static final Map<String, String[]> TEXTS = Map.ofEntries(
        entry("shard_key_missing", "chave de shard ausente", "missing shard key"),
        entry("shard_key_invalid", "um valor do tipo %s não serve de chave de shard",
            "a value of type %s cannot be a shard key"),
        entry("shard_map_invalid", "mapa dos shards inválido: %s", "invalid shard map: %s"),
        entry("shard_map_missing", "o mapa dos shards ainda não foi criado", "the shard map has not been created"),
        entry("shard_unavailable", "o shard %s não atendeu dentro do prazo: %s",
            "shard %s did not answer in time: %s"),
        entry("shard_unknown", "shard desconhecido: %s", "unknown shard: %s"));

    private ShardMessages() {
    }

    private static Map.Entry<String, String[]> entry(String key, String portuguese, String english) {
        return Map.entry(key, new String[] {portuguese, english});
    }

    static String get(String key, Object... args) {
        String[] texts = TEXTS.get(key);
        if (texts == null) {
            return key;
        }
        String text = "en".equals(Messages.language()) ? texts[1] : texts[0];
        return args.length == 0 ? text : String.format(text, args);
    }
}
