package com.axonbase.parser.ast;

import java.util.List;

/** Sentenças da AxonQL (o subconjunto da primeira entrega). */
public sealed interface Statement {

    record Use(Expr ns, Expr db) implements Statement {
    }

    record Let(String name, Expr value) implements Statement {
    }

    record Create(boolean only, Expr target, Data data, ReturnSpec ret) implements Statement {
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
     * SELECT. Com {@code valueOnly} ({@code SELECT VALUE x}), o resultado é a
     * lista dos valores do campo em vez de uma lista de objetos.
     */
    record Select(List<Expr> fields, boolean valueOnly, boolean only, Expr from, Expr cond,
                  List<OrderTerm> orders, List<Expr> group, Expr limit, Expr start,
                  List<String> fetch) implements Statement {
    }

    /** LIVE SELECT ... DIFF: subscrição em tempo real. */
    record Live(Select select, boolean diff) implements Statement {
    }

    record Relate(Expr from, Expr kind, Expr to, Data data, ReturnSpec ret) implements Statement {
    }

    record DefineTable(String name, boolean schemafull, boolean drop, Permissions permissions)
            implements Statement {
    }

    record DefineField(String name, String table, String type, Expr assertExpr, boolean readonly,
                       Expr valueExpr, Expr defaultExpr) implements Statement {
    }

    record DefineIndex(String name, String table, List<String> columns, boolean unique, boolean count,
                       String searchAnalyzer, boolean geo, Integer vectorDimension, String vectorDistance)
            implements Statement {
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
     * transportar a identidade sem conhecer nem rehashear a senha original.
     * Exatamente uma das duas é preenchida.</p>
     */
    record DefineUser(String name, AuthScope scope, String namespace, String database,
                      Expr password, String passhash, List<String> roles) implements Statement {

        public DefineUser {
            if ((password == null) == (passhash == null)) {
                throw new IllegalArgumentException("DEFINE USER exige PASSWORD ou PASSHASH, nunca ambos");
            }
        }

        /** A identidade já veio protegida por hash, sem senha em texto puro. */
        public boolean hashed() {
            return passhash != null;
        }
    }

    /** Access method nomeado, limitado a um escopo. */
    record DefineAccess(String name, AuthScope scope, String namespace, String database)
            implements Statement {
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
