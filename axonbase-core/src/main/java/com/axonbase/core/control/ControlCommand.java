package com.axonbase.core.control;

/**
 * Comando determinístico do plano de controle, replicável pelo log Raft.
 *
 * <p>{@code definition} guarda o texto AxonQL renderizado da definição. Reexecutar
 * esse texto reconstrói o catálogo, o que torna o replay e a replicação um único
 * mecanismo. Para {@link Kind#USER} o texto usa a cláusula {@code PASSHASH}, de
 * modo que o salt e o hash viajam juntos e a senha nunca é reprocessada.</p>
 */
public record ControlCommand(Kind kind, String namespace, String database, String name,
                             String definition) {

    /**
     * Tipos de definição no plano de controle. A ordem de declaração é a ordem de
     * replay: um índice com analyzer precisa do analyzer, um campo precisa da
     * tabela, e a tabela precisa do banco de dados.
     */
    public enum Kind {
        DATABASE, ANALYZER, TABLE, FIELD, INDEX, EVENT, ACCESS, USER
    }

    public ControlCommand {
        if (kind == null) {
            throw new IllegalArgumentException("kind do comando de controle é obrigatório");
        }
        if (name == null) {
            throw new IllegalArgumentException("name do comando de controle é obrigatório");
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
