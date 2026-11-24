package com.axonbase.core.pl;

import com.axonbase.core.engine.Datastore;
import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Query;
import com.axonbase.parser.ast.Statement;
import java.util.concurrent.ConcurrentHashMap;

/** SQL usado por {@code db.*}: análise com cache e classificação das sentenças. */
final class SqlText {

    /** Consulta já analisada e o que ela contém. */
    record Parsed(Query query, boolean readOnly, boolean ddl, boolean forbidden, String forbiddenWhat) {
    }

    private static final int MAX = 1024;
    private static final ConcurrentHashMap<String, Parsed> CACHE = new ConcurrentHashMap<>();

    private SqlText() {
    }

    static Parsed parse(String sql) {
        Parsed p = CACHE.get(sql);
        if (p != null) {
            return p;
        }
        Query q = AxonQl.parse(sql);
        boolean readOnly = true;
        boolean ddl = false;
        String forbidden = null;
        for (Statement s : q.statements()) {
            readOnly &= Datastore.isReadOnly(s);
            ddl |= !isData(s);
            if (forbidden == null) {
                forbidden = forbiddenIn(s);
            }
        }
        p = new Parsed(q, readOnly, ddl, forbidden != null, forbidden);
        if (CACHE.size() >= MAX) {
            CACHE.clear();
        }
        CACHE.put(sql, p);
        return p;
    }

    /** Controle de transação e de escopo é de quem chamou, não do programa. */
    private static String forbiddenIn(Statement s) {
        if (s instanceof Statement.Begin || s instanceof Statement.Commit || s instanceof Statement.Cancel) {
            return "BEGIN, COMMIT e CANCEL (a transacao e de quem chamou)";
        }
        if (s instanceof Statement.Use) {
            return "USE (o escopo e de quem chamou)";
        }
        if (s instanceof Statement.BeginSaga || s instanceof Statement.CommitSaga
            || s instanceof Statement.CancelSaga || s instanceof Statement.JoinSaga
            || s instanceof Statement.LeaveSaga) {
            return "comandos de SAGA";
        }
        if (s instanceof Statement.Live || s instanceof Statement.Kill) {
            return "LIVE e KILL";
        }
        return null;
    }

    /** Sentenças de dados e de leitura: o resto (DEFINE, CREATE TABLE, DROP, usuários...) é DDL ou administração. */
    private static boolean isData(Statement s) {
        return s instanceof Statement.Select || s instanceof Statement.Create || s instanceof Statement.Insert
            || s instanceof Statement.Update || s instanceof Statement.Delete || s instanceof Statement.Relate
            || s instanceof Statement.Unrelate || s instanceof Statement.Let || s instanceof Statement.Return
            || s instanceof Statement.IfElse || s instanceof Statement.Explain || s instanceof Statement.Info
            || s instanceof Statement.CallProgram || s instanceof Statement.DescribeTable
            || s instanceof Statement.Savepoint || s instanceof Statement.Release
            || s instanceof Statement.RollbackTo || s instanceof Statement.ErrorStmt
            || s instanceof Statement.ShowDataRules;
    }
}
