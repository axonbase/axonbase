package com.axonbase.core.qos;

import com.axonbase.core.Session;
import com.axonbase.core.engine.Datastore;
import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Query;
import com.axonbase.parser.ast.Statement;
import com.axonbase.value.AxonValue;

/**
 * Decide se um texto AxonQL é transacional ou analítico. Escritas, scripts que escrevem e comandos dentro de uma
 * transação explícita são sempre transacionais: fazê-los esperar com travas na mão atrasaria os outros.
 */
final class WorkloadClassifier {

    /** O que o governador faz com a consulta. */
    enum Decision {
        /** Transacional, e continua assim até o fim. */
        TRANSACTIONAL,
        /** Leitura que começa como transacional e é rebaixada se ler muito. */
        DEMOTABLE,
        /** Precisa de vaga e respeita o orçamento. */
        ANALYTICAL
    }

    static final String REQUEST_ANALYTICAL = "analytical";
    static final String REQUEST_TRANSACTIONAL = "transactional";

    private WorkloadClassifier() {
    }

    static Decision classify(QosSettings settings, String sql, Session session, String requested) {
        if (session != null && session.inTransaction()) {
            return Decision.TRANSACTIONAL;
        }
        String wanted = requested == null ? "" : requested.trim();
        boolean asksAnalytical = settings.analyticalUsers().contains(userOf(session))
            || wanted.equalsIgnoreCase(REQUEST_ANALYTICAL);
        if (asksAnalytical) {
            return readOnly(parse(sql)) ? Decision.ANALYTICAL : Decision.TRANSACTIONAL;
        }
        boolean asksTransactional = settings.trustClient() && wanted.equalsIgnoreCase(REQUEST_TRANSACTIONAL);
        if (asksTransactional || settings.mode() != QosSettings.Mode.AUTO) {
            return Decision.TRANSACTIONAL;
        }
        Query query = parse(sql);
        if (!readOnly(query)) {
            return Decision.TRANSACTIONAL;
        }
        return query.statements().stream().anyMatch(WorkloadClassifier::heavy)
            ? Decision.ANALYTICAL : Decision.DEMOTABLE;
    }

    private static Query parse(String sql) {
        try {
            return AxonQl.parse(sql);
        } catch (RuntimeException invalid) {
            // o erro de sintaxe sai na execução
            return null;
        }
    }

    private static boolean readOnly(Query query) {
        return query != null && !query.statements().isEmpty() && query.statements().stream()
            .allMatch(st -> Datastore.isReadOnly(st) && !(st instanceof Statement.Begin)
                && !(st instanceof Statement.Commit) && !(st instanceof Statement.Cancel));
    }

    /** O texto já mostra que a consulta percorre uma tabela inteira ou agrega muitas linhas. */
    private static boolean heavy(Statement statement) {
        if (!(statement instanceof Statement.Select select) || select.only()) {
            return false;
        }
        if (select.grouped()) {
            return true;
        }
        if (select.from() instanceof Expr.SubQuery sub) {
            return sub.query().statements().stream().anyMatch(WorkloadClassifier::heavy);
        }
        boolean wholeTable = select.from() instanceof Expr.Ident || select.from() instanceof Expr.Qualified;
        return wholeTable && select.cond() == null && select.limit() == null;
    }

    /** O identificador do usuário autenticado, ou vazio. */
    private static String userOf(Session session) {
        AxonValue auth = session == null ? null : session.auth();
        if (auth == null || !auth.isObject()) {
            return "";
        }
        AxonValue id = auth.asObject().get("id");
        return id == null || !id.isString() ? "" : id.asString();
    }
}
