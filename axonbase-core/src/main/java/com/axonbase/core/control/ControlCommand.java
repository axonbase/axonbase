package com.axonbase.core.control;

import com.axonbase.common.Messages;

/**
 * Comando determinístico do plano de controle, replicável pelo log Raft.
 *
 * <p>{@code definition} guarda o texto AxonQL renderizado da definição. Reexecutar
 * esse texto reconstrói o catálogo, o que torna o replay e a replicação um único
 * mecanismo. Para {@link Kind#USER}, identidades por senha usam a cláusula
 * {@code PASSHASH}, de modo que o salt e o hash viajam juntos sem reprocessar a
 * senha; identidades por certificado preservam apenas o identificador do JKS e
 * o fingerprint.</p>
 */
public record ControlCommand(Kind kind, String namespace, String database, String name,
                             String definition) {

    /**
     * Tipos de definição no plano de controle. A ordem de replay é a de {@link #rank()}:
     * um índice com analyzer precisa do analyzer, um campo precisa da tabela, e a
     * tabela precisa do banco de dados.
     */
    public enum Kind {
        DATABASE, DATABASE_LINK, ANALYZER, EMBEDDING, TABLE, FIELD, INDEX, EVENT,
        /** Objetos AxonPL (definição em AxonQL com o corpo entre $$). Packages antes de funções e procedures. */
        PACKAGE, FUNCTION, PROCEDURE,
        ACCESS, USER, AUDIT,
        /** Truststore enviado pela API: guarda só nome e caminho do arquivo, nunca a senha. */
        JKS,
        /** Partição por tempo e prazo de validade de uma tabela (o nome é a tabela). Reexecutados logo depois dela. */
        PARTITIONING, TTL,
        /** Definição de SAGA: global (sem namespace nem banco), com o nome da SAGA. */
        SAGA
    }

    public ControlCommand {
        if (kind == null) {
            throw new IllegalArgumentException(Messages.get("control_kind_required"));
        }
        if (name == null) {
            throw new IllegalArgumentException(Messages.get("control_name_required"));
        }
        namespace = namespace == null ? "" : namespace;
        database = database == null ? "" : database;
        definition = definition == null ? "" : definition;
    }

    /** Chave de identidade: reaplicar o mesmo comando substitui a definição anterior. */
    public String identity() {
        return kind.name() + '\u0000' + namespace + '\u0000' + database + '\u0000' + name;
    }

    /**
     * Posição relativa deste comando na ordem de replay: a ordem de declaração, menos {@code PARTITIONING} e
     * {@code TTL}, que vêm logo depois de {@code TABLE}. Reexecutar um índice pode retomar a construção dele na hora,
     * e ela precisa saber que a tabela é particionada para não ficar pronta sem linha alguma.
     */
    public int rank() {
        return switch (kind) {
            case PARTITIONING -> Kind.TABLE.ordinal() * 10 + 1;
            case TTL -> Kind.TABLE.ordinal() * 10 + 2;
            default -> kind.ordinal() * 10;
        };
    }

    public static ControlCommand database(String namespace, String database) {
        return new ControlCommand(Kind.DATABASE, namespace, database, namespace + '/' + database, "");
    }

    public static ControlCommand of(Kind kind, String namespace, String database, String name,
                                    String definition) {
        return new ControlCommand(kind, namespace, database, name, definition);
    }
}
