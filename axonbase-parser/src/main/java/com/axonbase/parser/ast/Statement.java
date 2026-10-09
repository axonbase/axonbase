package com.axonbase.parser.ast;

import com.axonbase.common.Messages;
import java.util.List;

/** Sentenças da AxonQL (o subconjunto da primeira entrega). */
public sealed interface Statement {

    record Use(Expr ns, Expr db) implements Statement {
    }

    record Let(String name, Expr value) implements Statement {
    }

    record Create(boolean only, Expr target, Data data, ReturnSpec ret) implements Statement {
    }

    record CreateJks(String name, String path, String password, String collector, List<String> oids) implements Statement {
    }

    record Insert(boolean ignore, boolean relation, String table, Expr data, ReturnSpec ret)
            implements Statement {
    }

    record Update(UpdateMode mode, boolean only, Expr target, Data data, Expr cond, ReturnSpec ret)
            implements Statement {
    }

    record Delete(Expr target, Expr cond, ReturnSpec ret) implements Statement {
    }

    /**
     * SELECT. Con {@code valueOnly} ({@code SELECT VALUE x}), o resultado é a
     * lista dos valores do campo en vez de unha lista de obxectos.
     */
    record Select(List<Expr> fields, boolean valueOnly, boolean only, Expr from, Expr cond,
                   List<OrderTerm> orders, List<Expr> group, Expr limit, Expr start,
                   List<String> fetch, List<Join> joins, TimeTravel timeTravel) implements Statement {
    }

    /** Ponto histórico de leitura de um SELECT. */
    record TimeTravel(TimeTravelMode mode, TimeTravelSelector selector, Expr value) {
    }

    enum TimeTravelMode {
        AT, BEFORE
    }

    enum TimeTravelSelector {
        TIMESTAMP, COMMIT, STATEMENT
    }

    /**
     * Unión dunha orixe co par clave a seguir no EXECUTE: {@code a} é a fonte
     * externa (o {@code FROM}), {@code b} a fonte enlazada. A igualdade vale sobre
     * {@code left} (=) {@code right}.
     */
    record Join(Expr source, Expr left, Expr right) implements Statement {
    }

    /** LIVE SELECT ... DIFF: subscrição em tempo real. */
    record Live(Select select, boolean diff) implements Statement {
    }

    /**
     * EXPLAIN SELECT ... [ANALYZE]... Despide un object co plan de execución
     * (strategy, índice, filas examinadas) sen executar o SELECT, ou execútano
     * cando {@code analyze} é certo para contar as filas reais.
     */
    record Explain(Select select, boolean analyze) implements Statement {
    }

    record Relate(Expr from, Expr kind, Expr to, Data data, ReturnSpec ret) implements Statement {
    }

    record DefineTable(String name, boolean schemafull, boolean drop, Permissions permissions)
            implements Statement {
    }

    record ColumnDef(String name, String type, boolean primaryKey, boolean notNull,
                     Expr defaultExpr, Expr checkExpr, String references) {
    }

    record CreateTable(String name, boolean ifNotExists, boolean schemafull,
                       List<ColumnDef> columns) implements Statement {
        public CreateTable {
            columns = columns == null ? List.of() : List.copyOf(columns);
        }
    }

    record DefineField(String name, String table, String type, Expr assertExpr, boolean readonly,
                       Expr valueExpr, Expr defaultExpr, String references) implements Statement {
    }

    record DefineIndex(String name, String table, List<String> columns, boolean unique, boolean count,
                       String searchAnalyzer, boolean geo, boolean columnar,
                       Integer vectorDimension, String vectorDistance,
                       Integer m, Integer efConstruction, Integer efSearch)
            implements Statement {

        public DefineIndex {
            columns = List.copyOf(columns);
        }
    }

    /** Analisador para índices full-text: tokeniza, normaliza e remove stopwords. */
    record DefineAnalyzer(String name, boolean lowercase, List<String> stopwords, boolean stemming)
            implements Statement {
    }

    record DefineEvent(String name, String table, Expr when, List<Statement> then)
            implements Statement {
    }

    /**
     * Usuário autenticável em um escopo ROOT, NAMESPACE ou DATABASE.
     *
     * <p>A senha chega em texto puro pela cláusula {@code PASSWORD} ou já
     * protegida pela cláusula {@code PASSHASH "&lt;salt&gt;:&lt;hash&gt;"}. A segunda forma
     * existe para que o plano de controle replicado e o dump de catálogo possam
     * transportar a identidade sem conhecer nem rehashear a senha original. Como
     * alternativa, {@code CERTIFICATE} referencia um JKS previamente criado e pode
     * restringir o certificado por {@code FINGERPRINT}. Exatamente uma credencial é
     * preenchida.</p>
     */
    record DefineUser(String name, AuthScope scope, String namespace, String database,
                       Expr password, String passhash, String certificate, String fingerprint,
                       List<String> roles, List<String> dataRules, String auditName) implements Statement {

        public DefineUser {
            int credentials = (password == null ? 0 : 1) + (passhash == null ? 0 : 1)
                + (certificate == null ? 0 : 1);
            if (credentials != 1) {
                throw new IllegalArgumentException(Messages.get("parser_define_user_credential_count"));
            }
            if (fingerprint != null && certificate == null) {
                throw new IllegalArgumentException(Messages.get("parser_fingerprint_requires_certificate"));
            }
            roles = roles == null ? List.of() : List.copyOf(roles);
            dataRules = dataRules == null ? List.of() : List.copyOf(dataRules);
        }

        public DefineUser(String name, AuthScope scope, String namespace, String database,
                          Expr password, String passhash, List<String> roles) {
            this(name, scope, namespace, database, password, passhash, null, null, roles, null, null);
        }

        /** A identidade já veio protegida por hash, sem senha em texto puro. */
        public boolean hashed() {
            return passhash != null;
        }

        public boolean certificateBased() {
            return certificate != null;
        }
    }

    /** Access method nomeado, limitado a um escopo. */
    record DefineAccess(String name, AuthScope scope, String namespace, String database)
            implements Statement {
    }

    record DefineDatabaseLink(String name, String url, String ns, String db,
                               String user, String password) implements Statement {
    }

    record DropDatabaseLink(String name) implements Statement {
    }

    // ------------------------------------------------------------------
    // SAGA (transações distribuídas orquestradas por DATABASE LINK)
    // ------------------------------------------------------------------

    /** CREATE SAGA &lt;nome&gt; WITH DATABASES 'link1', 'link2', ... */
    record CreateSaga(String name, List<String> links) implements Statement {

        public CreateSaga {
            links = List.copyOf(links);
        }
    }

    /** DESCRIBE SAGA &lt;nome&gt;: metadados e links do recurso. */
    record DescribeSaga(String name) implements Statement {
    }

    /** DESCRIBE &lt;tabela&gt;: nome, modo de schema e campos da tabela. */
    record DescribeTable(String table) implements Statement {
    }

    /** SHOW SAGA TRANSACTION &lt;nome&gt; 'corr-id': ledger da transação. */
    record ShowSagaTransaction(String name, String correlationId) implements Statement {
    }

    /** BEGIN SAGA &lt;nome&gt; WITH CORRELATION 'corr-id'. */
    record BeginSaga(String name, String correlationId) implements Statement {
    }

    /** COMMIT SAGA &lt;nome&gt; WITH CORRELATION 'corr-id'. */
    record CommitSaga(String name, String correlationId) implements Statement {
    }

    /** CANCEL SAGA &lt;nome&gt; WITH CORRELATION 'corr-id': compensação reversa. */
    record CancelSaga(String name, String correlationId) implements Statement {
    }

    /** JOIN SAGA &lt;nome&gt; WITH CORRELATION 'corr-id'. */
    record JoinSaga(String name, String correlationId) implements Statement {
    }

    /** LEAVE SAGA. */
    record LeaveSaga() implements Statement {
    }

    record Info(String kind, String table) implements Statement {
    }

    record Return(Expr value) implements Statement {
    }

    record IfElse(List<Branch> branches, Expr elseBranch) implements Statement {
    }

    record Kill(Expr target) implements Statement {
    }

    record ErrorStmt(Expr message) implements Statement {
    }

    record Begin() implements Statement {
    }

    record Commit() implements Statement {
    }

    record Cancel() implements Statement {
    }

    /** SAVEPOINT &lt;nome&gt;: marca un checkpoint na transacción activa. */
    record Savepoint(String name) implements Statement {
    }

    /** RELEASE [SAVEPOINT] &lt;nome&gt;: elimina o marcador sen botar os writes. */
    record Release(String name) implements Statement {
    }

    /** ROLLBACK TO [SAVEPOINT] &lt;nome&gt;: reverte os writes/deletes ao checkpoint. */
    record RollbackTo(String name) implements Statement {
    }

    /** CREATE DATA RULE &lt;nome&gt; APPLY &lt;predicate&gt; [MASK FIELDS WITH 'p1', 'p2', ...] */
    record CreateDataRule(String name, Expr predicate, List<String> maskPatterns) implements Statement {
        public CreateDataRule {
            maskPatterns = maskPatterns == null ? List.of() : List.copyOf(maskPatterns);
        }
    }

    /** DROP DATA RULE &lt;nome&gt; */
    record DropDataRule(String name) implements Statement {
    }

    /** SHOW DATA RULES: lista as regras definidas no catálogo. */
    record ShowDataRules() implements Statement {
    }

    // ------------------------------------------------------------------
    // AI AUDIT
    // ------------------------------------------------------------------

    record DefineAiAudit(String name, String warningWhen, String dangerWhen) implements Statement {
    }

    record DropAiAudit(String name) implements Statement {
    }

    /** REMOVE TABLE {@code <nome>} */
    record RemoveTable(String name) implements Statement {
    }

    // ------------------------------------------------------------------
    // SQL Standard syntax (CREATE/ALTER/DROP)
    // ------------------------------------------------------------------

    /** DROP TABLE {@code <name>} */
    record DropTable(String name) implements Statement {
    }

    sealed interface AlterOp {
    }

    record AddColumn(String name, String type, boolean primaryKey, boolean notNull,
                     Expr defaultExpr, Expr checkExpr, String references) implements AlterOp {
    }

    record DropColumn(String name) implements AlterOp {
    }

    record ModifyColumn(String name, String type, boolean notNull,
                        Expr defaultExpr, Expr checkExpr) implements AlterOp {
    }

    /** ALTER TABLE {@code <name>} {op1, op2, ...} */
    record AlterTable(String name, List<AlterOp> operations) implements Statement {
        public AlterTable {
            operations = List.copyOf(operations);
        }
    }

    /** CREATE INDEX (padrão SQL) */
    record CreateIndex(String name, String table, List<String> columns, boolean unique, boolean count,
                       String searchAnalyzer, boolean geo, boolean columnar,
                       Integer vectorDimension, String vectorDistance,
                       Integer m, Integer efConstruction, Integer efSearch)
            implements Statement {
        public CreateIndex {
            columns = List.copyOf(columns);
        }
    }

    /** DROP INDEX {@code <name>} */
    record DropIndex(String name) implements Statement {
    }

    /** CREATE EVENT SQL padrão */
    record CreateEvent(String name, String table, Expr when, List<Statement> then)
            implements Statement {
    }

    /** DROP EVENT {@code <name>} */
    record DropEvent(String name) implements Statement {
    }

    /** CREATE ANALYZER SQL padrão */
    record CreateAnalyzer(String name, boolean lowercase, List<String> stopwords, boolean stemming)
            implements Statement {
    }

    /** DROP ANALYZER {@code <name>} */
    record DropAnalyzer(String name) implements Statement {
    }

    /** CREATE USER SQL padrão */
    record CreateUser(String name, AuthScope scope, String namespace, String database,
                      Expr password, String passhash, String certificate, String fingerprint,
                      List<String> roles, List<String> dataRules, String auditName) implements Statement {

        public CreateUser {
            int credentials = (password == null ? 0 : 1) + (passhash == null ? 0 : 1)
                + (certificate == null ? 0 : 1);
            if (credentials != 1) {
                throw new IllegalArgumentException(Messages.get("parser_define_user_credential_count"));
            }
            if (fingerprint != null && certificate == null) {
                throw new IllegalArgumentException(Messages.get("parser_fingerprint_requires_certificate"));
            }
            roles = roles == null ? List.of() : List.copyOf(roles);
            dataRules = dataRules == null ? List.of() : List.copyOf(dataRules);
        }

        public CreateUser(String name, AuthScope scope, String namespace, String database,
                          Expr password, String passhash, List<String> roles) {
            this(name, scope, namespace, database, password, passhash, null, null, roles, null, null);
        }

        public boolean hashed() {
            return passhash != null;
        }

        public boolean certificateBased() {
            return certificate != null;
        }
    }

    /** DROP USER {@code <name>} */
    record DropUser(String name) implements Statement {
    }

    /** GRANT ACCESS (substitui DEFINE ACCESS) */
    record GrantAccess(String name, AuthScope scope, String namespace, String database)
            implements Statement {
    }

    /** REVOKE ACCESS {@code <name>} */
    record RevokeAccess(String name) implements Statement {
    }

    /** CREATE DATABASE LINK SQL padrão */
    record CreateDatabaseLink(String name, String url, String ns, String db,
                              String user, String password) implements Statement {
    }

    /** ALTER DATABASE LINK {@code <name>} CONNECT BY ... */
    record AlterDatabaseLink(String name, String url, String ns, String db,
                             String user, String password) implements Statement {
    }

    /** DROP SAGA {@code <name>} */
    record DropSaga(String name) implements Statement {
    }

    record ShowAiAudit(String name) implements Statement {
    }

    record SetReasonAudit(String hash, String reason) implements Statement {
    }

    record SetAuditCase(String hash, String status, String reason) implements Statement {
    }

    // ------------------------------------------------------------------
    // tipos auxiliares das sentenças
    // ------------------------------------------------------------------

    enum UpdateMode {
        UPDATE, UPSERT
    }

    /** Permisos dunha táboa, por acción (select/create/update/delete). */
    record Permissions(Expr select, Expr create, Expr update, Expr delete) {

        public static Permissions none() {
            return new Permissions(null, null, null, null);
        }

        public static Permissions full() {
            var t = new Expr.Literal(com.axonbase.value.AxonValue.bool(true));
            return new Permissions(t, t, t, t);
        }
    }

    /** Dados de escrita de CREATE/UPDATE/INSERT/RELATE. */
    sealed interface Data {

        record Content(Expr content) implements Data {
        }

        record SetClause(List<Assignment> assignments) implements Data {
        }

        record Merge(Expr merge) implements Data {
        }

        record Patch(Expr patch) implements Data {
        }

        record Replace(Expr replace) implements Data {
        }
    }

    record Assignment(Expr path, Expr value) {
    }

    /** Especificação de RETURN de uma sentença de escrita. */
    enum ReturnKind {
        BEFORE, AFTER, NONE, DIFF, EXPR
    }

    record ReturnSpec(ReturnKind kind, Expr expr) {
        public static final ReturnSpec AFTER = new ReturnSpec(ReturnKind.AFTER, null);
        public static final ReturnSpec NONE = new ReturnSpec(ReturnKind.NONE, null);
    }

    record OrderTerm(Expr field, boolean descending) {
    }

    record Branch(Expr cond, Expr thenExpr) {
    }

    enum AuthScope {
        ROOT, NAMESPACE, DATABASE
    }
}
