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
     * Tipos de definição no plano de controle. A ordem de declaração é a ordem de
     * replay: um índice com analyzer precisa do analyzer, um campo precisa da
     * tabela, e a tabela precisa do banco de dados.
     */
    public enum Kind {
        DATABASE, DATABASE_LINK, ANALYZER, TABLE, FIELD, INDEX, EVENT, ACCESS, USER, AUDIT
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

    /** Posição relativa deste comando na ordem de replay. */
    public int rank() {
        return kind.ordinal();
    }

    public static ControlCommand database(String namespace, String database) {
        return new ControlCommand(Kind.DATABASE, namespace, database, namespace + '/' + database, "");
    }

    public static ControlCommand of(Kind kind, String namespace, String database, String name,
                                    String definition) {
        return new ControlCommand(kind, namespace, database, name, definition);
    }
}
