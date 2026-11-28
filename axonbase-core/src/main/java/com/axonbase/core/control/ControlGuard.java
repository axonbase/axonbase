package com.axonbase.core.control;

import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.PartitionCommand;
import com.axonbase.parser.ast.Query;
import com.axonbase.parser.ast.Statement;

import java.util.List;
import java.util.Optional;

/**
 * O que um registro do plano de controle pode conter: a definição do próprio tipo e do próprio nome, e nada mais. O
 * texto é reexecutado sem identidade, então qualquer outra sentença nele valeria como a de um administrador.
 */
public final class ControlGuard {

    private ControlGuard() {
    }

    /** A definição analisada, ou vazio quando ela não é o que o registro diz ser (ou nem é AxonQL). */
    public static Optional<Query> replayable(ControlCommand command) {
        Query query;
        try {
            query = AxonQl.parse(command.definition());
        } catch (RuntimeException e) {
            return Optional.empty();
        }
        return matches(command, query.statements()) ? Optional.of(query) : Optional.empty();
    }

    private static boolean matches(ControlCommand command, List<Statement> statements) {
        // a tabela é gravada como CREATE TABLE (colunas) seguido de DEFINE TABLE (opções)
        if (command.kind() == ControlCommand.Kind.TABLE && statements.size() == 2) {
            return statements.get(0) instanceof Statement.CreateTable columns
                && same(command, columns.name())
                && statements.get(1) instanceof Statement.DefineTable options
                && same(command, options.name());
        }
        return statements.size() == 1 && matches(command, statements.get(0));
    }

    /** Os {@code DROP} entram porque versões anteriores gravavam alguns deles. */
    private static boolean matches(ControlCommand command, Statement statement) {
        return switch (command.kind()) {
            case DATABASE_LINK -> switch (statement) {
                case Statement.DefineDatabaseLink s -> same(command, s.name());
                case Statement.CreateDatabaseLink s -> same(command, s.name());
                case Statement.AlterDatabaseLink s -> same(command, s.name());
                case Statement.DropDatabaseLink s -> same(command, s.name());
                default -> false;
            };
            case ANALYZER -> switch (statement) {
                case Statement.DefineAnalyzer s -> same(command, s.name());
                case Statement.CreateAnalyzer s -> same(command, s.name());
                case Statement.DropAnalyzer s -> same(command, s.name());
                default -> false;
            };
            case EMBEDDING -> switch (statement) {
                case Statement.DefineEmbedding s -> same(command, s.name());
                case Statement.DropEmbedding s -> same(command, s.name());
                default -> false;
            };
            case TABLE -> switch (statement) {
                case Statement.DefineTable s -> same(command, s.name());
                case Statement.CreateTable s -> same(command, s.name());
                case Statement.AlterTable s -> same(command, s.name());
                case Statement.DropTable s -> same(command, s.name());
                case Statement.RemoveTable s -> same(command, s.name());
                case Statement.CreateDataRule s -> same(command, "datarule:" + s.name());
                case Statement.DropDataRule s -> same(command, "datarule:" + s.name());
                default -> false;
            };
            case FIELD -> statement instanceof Statement.DefineField s && same(command, s.table() + ":" + s.name());
            case INDEX -> switch (statement) {
                case Statement.DefineIndex s -> owned(command, s.table(), s.name());
                case Statement.CreateIndex s -> owned(command, s.table(), s.name());
                case Statement.DropIndex s -> owned(command, s.table(), s.name());
                default -> false;
            };
            case EVENT -> switch (statement) {
                case Statement.DefineEvent s -> owned(command, s.table(), s.name());
                case Statement.CreateEvent s -> owned(command, s.table(), s.name());
                case Statement.DropEvent s -> owned(command, null, s.name());
                default -> false;
            };
            case PACKAGE -> statement instanceof Statement.CreatePackage s && same(command, s.name())
                || statement instanceof Statement.DropProgram d && same(command, d.name());
            case FUNCTION -> statement instanceof Statement.CreateFunction s && same(command, s.name())
                || statement instanceof Statement.DropProgram d && same(command, d.name());
            case PROCEDURE -> statement instanceof Statement.CreateProcedure s && same(command, s.name())
                || statement instanceof Statement.DropProgram d && same(command, d.name());
            case ACCESS -> switch (statement) {
                case Statement.DefineAccess s -> identity(command, s.name());
                case Statement.GrantAccess s -> identity(command, s.name());
                case Statement.RevokeAccess s -> identity(command, s.name());
                default -> false;
            };
            case USER -> switch (statement) {
                case Statement.DefineUser s -> identity(command, s.name());
                case Statement.CreateUser s -> identity(command, s.name());
                case Statement.DropUser s -> identity(command, s.name());
                default -> false;
            };
            case AUDIT -> statement instanceof Statement.DefineAiAudit s && same(command, s.name())
                || statement instanceof Statement.DropAiAudit d && same(command, d.name());
            case PARTITIONING -> statement instanceof Statement.Partition p
                && (p.command() instanceof PartitionCommand.Define d && same(command, d.table())
                || p.command() instanceof PartitionCommand.Drop x && same(command, x.table()));
            case TTL -> statement instanceof Statement.Partition p
                && (p.command() instanceof PartitionCommand.DefineTtl d && same(command, d.table())
                || p.command() instanceof PartitionCommand.DropTtl x && same(command, x.table()));
            case SAGA -> statement instanceof Statement.CreateSaga s && same(command, s.name())
                || statement instanceof Statement.DropSaga d && same(command, d.name());
            case DATABASE, JKS -> false;
        };
    }

    /** Sem diferenciar maiúsculas: versões anteriores gravavam sem crases nomes que são palavras-chave. */
    private static boolean same(ControlCommand command, String name) {
        return command.name().equalsIgnoreCase(name);
    }

    private static boolean owned(ControlCommand command, String table, String name) {
        if (same(command, name)) {
            return true;
        }
        if (table != null) {
            return same(command, table + ":" + name);
        }
        return command.name().toLowerCase(java.util.Locale.ROOT)
            .endsWith(":" + name.toLowerCase(java.util.Locale.ROOT));
    }

    /** Usuários e acessos são registrados como {@code nome:escopo:ns:db}. */
    private static boolean identity(ControlCommand command, String name) {
        return same(command, name) || command.name().regionMatches(true, 0, name + ":", 0, name.length() + 1);
    }
}
