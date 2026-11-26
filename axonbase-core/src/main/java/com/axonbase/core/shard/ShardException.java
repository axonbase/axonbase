package com.axonbase.core.shard;

/** Erro do roteamento entre shards, com um código que o transporte e o cliente sabem tratar. */
public final class ShardException extends RuntimeException {

    public enum Code {
        /** A instrução não é suportada entre shards, ou viola uma regra. Nada foi executado. */
        REFUSED,
        /** Os slots estão em movimentação: nada foi executado, vale repetir. */
        MOVING,
        /** O mapa dos shards não pôde ser renovado dentro do prazo. Nada foi executado. */
        STALE_MAP,
        /** Nenhum nó do shard atendeu dentro do prazo. Nada foi executado. */
        UNAVAILABLE,
        /** Parte dos shards gravou e parte não. A mensagem diz quais. */
        PARTIAL_WRITE
    }

    private final Code code;

    public ShardException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public ShardException(Code code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public Code code() {
        return code;
    }

    static ShardException refused(String key, Object... args) {
        return new ShardException(Code.REFUSED, ShardMessages.get(key, args));
    }
}
