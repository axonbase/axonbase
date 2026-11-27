package com.axonbase.parser;

import com.axonbase.common.Messages;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Query;
import com.axonbase.parser.ast.Statement;
import com.axonbase.parser.ast.Statement.ReturnSpec;
import com.axonbase.value.AxonQlText;
import com.axonbase.value.AxonValue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Renderizador canônico da AxonQL, usado nos testes de round-trip. */
public final class Render {

    private static final int RANGE_PRECEDENCE = 5;

    private static final int PREFIX_PRECEDENCE = 9;

    private static final int ATOM_PRECEDENCE = 10;

    private static final Set<String> NOT_CALLABLE = Set.of("true", "false", "null", "none", "not", "select");

    /** Palavras que, logo depois de CREATE, começam outro comando. */
    private static final Set<String> AFTER_CREATE = Set.of("table", "saga", "ai", "jks", "data", "database");

    private static final Set<String> STATEMENT_WORDS = Set.of("select", "create", "update", "delete", "insert",
        "define", "let", "return", "if", "for", "info", "relate", "begin", "commit", "cancel", "alter", "drop",
        "grant", "revoke", "unrelate", "reembed");

    private Render() {
    }

    public static String query(Query q) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < q.statements().size(); i++) {
            if (i > 0) {
                sb.append("; ");
            }
            statement(sb, q.statements().get(i));
        }
        return sb.toString();
    }

    public static String stmt(Statement s) {
        StringBuilder sb = new StringBuilder();
        statement(sb, s);
        return sb.toString();
    }

    private static void statement(StringBuilder sb, Statement s) {
        switch (s) {
            case Statement.Use u -> {
                // posicional: "NS" e "DB" não são palavras-chave e o analisador os lê como nomes
                sb.append("USE");
                if (u.ns() != null) {
                    sb.append(' ').append(useName(u.ns()));
                }
                if (u.db() != null) {
                    sb.append(' ').append(useName(u.db()));
                }
            }
            case Statement.Let l -> sb.append("LET $").append(l.name()).append(" = ").append(expr(l.value()));
            case Statement.Create c -> {
                sb.append("CREATE ");
                if (c.only()) {
                    sb.append("ONLY ");
                }
                sb.append(createTarget(c.target()));
                data(sb, c.data());
                ret(sb, c.ret());
            }
            case Statement.CreateTable ct -> {
                sb.append("CREATE TABLE ");
                if (ct.ifNotExists()) {
                    sb.append("IF NOT EXISTS ");
                }
                sb.append(id(ct.name())).append(" (");
                for (int i = 0; i < ct.columns().size(); i++) {
                    if (i > 0) sb.append(", ");
                    Statement.ColumnDef col = ct.columns().get(i);
                    sb.append(id(col.name())).append(' ').append(typeText(col.type().toUpperCase()));
                    if (col.primaryKey()) sb.append(" PRIMARY KEY");
                    if (col.notNull()) sb.append(" NOT NULL");
                    if (col.defaultExpr() != null) sb.append(" DEFAULT ").append(expr(col.defaultExpr()));
                    if (col.checkExpr() != null) sb.append(" CHECK (").append(expr(col.checkExpr())).append(')');
                    if (col.references() != null) sb.append(" REFERENCES ").append(id(col.references()));
                }
                for (int i = 0; i < ct.checks().size(); i++) {
                    if (i > 0 || !ct.columns().isEmpty()) sb.append(", ");
                    sb.append("CHECK (").append(expr(ct.checks().get(i))).append(')');
                }
                sb.append(") WITH (SCHEMA = '").append(ct.schemafull() ? "FULL" : "SCHEMALESS").append("')");
            }
            case Statement.CreateJks jks -> {
                sb.append("CREATE JKS ").append(id(jks.name()))
                    .append(" PATH \"").append(escape(jks.path())).append("\" PASSWORD \"")
                    .append(escape(jks.password())).append('"');
                if ("ICPBRASIL".equals(jks.collector())) {
                    sb.append(" COLLECT USER BY ICPBRASIL");
                } else if ("OID".equals(jks.collector())) {
                    sb.append(" COLLECT USER BY OID ");
                    for (int i = 0; i < jks.oids().size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append('"').append(escape(jks.oids().get(i))).append('"');
                    }
                }
            }
            case Statement.Insert i -> {
                sb.append("INSERT ");
                if (i.ignore()) {
                    sb.append("IGNORE ");
                }
                if (i.relation()) {
                    sb.append("RELATION ");
                }
                sb.append("INTO ").append(id(i.table())).append(' ');
                // entre parênteses o SELECT seria lido como lista de colunas
                sb.append(i.data() instanceof Expr.SubQuery rows ? query(rows.query()) : expr(i.data()));
                ret(sb, i.ret());
            }
            case Statement.Update u -> {
                sb.append(u.mode() == Statement.UpdateMode.UPDATE ? "UPDATE " : "UPSERT ");
                if (u.only()) {
                    sb.append("ONLY ");
                }
                sb.append(expr(u.target()));
                data(sb, u.data());
                if (u.cond() != null) {
                    sb.append(" WHERE ").append(expr(u.cond()));
                }
                ret(sb, u.ret());
            }
            case Statement.Delete d -> {
                sb.append("DELETE ").append(expr(d.target()));
                if (d.cond() != null) {
                    sb.append(" WHERE ").append(expr(d.cond()));
                }
                ret(sb, d.ret());
            }
            case Statement.Select s2 -> {
                sb.append("SELECT ");
                if (s2.valueOnly()) {
                    sb.append("VALUE ");
                }
                for (int i = 0; i < s2.fields().size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    Expr field = s2.fields().get(i);
                    sb.append(i == 0 && !s2.valueOnly() ? firstField(field) : expr(field));
                }
                if (s2.only()) {
                    sb.append(" ONLY");
                }
                // SELECT VALUE sem FROM
                if (s2.from() != null) {
                    sb.append(" FROM ").append(expr(s2.from()));
                }
                if (s2.timeTravel() != null) {
                    var travel = s2.timeTravel();
                    sb.append(travel.mode() == Statement.TimeTravelMode.AT ? " AT (" : " BEFORE (")
                        .append(travel.selector()).append(" => ").append(expr(travel.value())).append(')');
                }
                for (var join : s2.joins()) {
                    sb.append(" JOIN ").append(expr(join.source()))
                        .append(" ON ").append(expr(join.left()))
                        .append(" = ")
                        .append(grouped(join.right(), precedence(join.right()) <= precedence(Expr.BinaryOp.EQ)));
                }
                if (s2.cond() != null) {
                    sb.append(" WHERE ").append(expr(s2.cond()));
                }
                if (s2.groupAll()) {
                    sb.append(" GROUP ALL");
                } else if (!s2.group().isEmpty()) {
                    sb.append(" GROUP BY ");
                    for (int i = 0; i < s2.group().size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(expr(s2.group().get(i)));
                    }
                }
                if (s2.having() != null) {
                    sb.append(" HAVING ").append(expr(s2.having()));
                }
                if (!s2.orders().isEmpty()) {
                    sb.append(" ORDER BY ");
                    for (int i = 0; i < s2.orders().size(); i++) {
                        var o = s2.orders().get(i);
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(expr(o.field()));
                        if (o.descending()) {
                            sb.append(" DESC");
                        }
                    }
                }
                if (s2.limit() != null) {
                    sb.append(" LIMIT ").append(expr(s2.limit()));
                }
                if (s2.start() != null) {
                    sb.append(" START ").append(expr(s2.start()));
                }
                if (!s2.fetch().isEmpty()) {
                    sb.append(" FETCH ").append(names(s2.fetch()));
                }
            }
            case Statement.Live lv -> {
                sb.append("LIVE ");
                statement(sb, lv.select());
                if (lv.diff()) {
                    sb.append(" DIFF");
                }
            }
            case Statement.Explain ex -> {
                sb.append("EXPLAIN ");
                if (ex.analyze()) {
                    sb.append("ANALYZE ");
                }
                statement(sb, ex.select());
            }
            case Statement.Relate r -> {
                sb.append("RELATE ").append(expr(r.from())).append("->").append(expr(r.kind()));
                if (r.to() != null) {
                    sb.append("->").append(expr(r.to()));
                }
                data(sb, r.data());
                ret(sb, r.ret());
            }
            case Statement.Unrelate u -> {
                sb.append("UNRELATE ").append(expr(u.from())).append("->").append(expr(u.kind()));
                if (u.to() != null) {
                    sb.append("->").append(expr(u.to()));
                }
            }
            case Statement.DefineTable dt -> {
                sb.append("DEFINE TABLE ").append(id(dt.name()));
                if (dt.schemafull()) {
                    sb.append(" SCHEMAFULL");
                } else {
                    sb.append(" SCHEMALESS");
                }
                if (dt.drop()) {
                    sb.append(" DROP");
                }
                if (dt.shardBy() != null) {
                    sb.append(" SHARD BY ").append(id(dt.shardBy()));
                }
                renderPermissions(sb, dt.permissions());
            }
            case Statement.DefineField df -> {
                sb.append("DEFINE FIELD ").append(path(df.name())).append(" ON TABLE ").append(id(df.table()));
                if (df.type() != null) {
                    sb.append(" TYPE ").append(typeText(df.type()));
                }
                if (df.assertExpr() != null) {
                    sb.append(" ASSERT ").append(expr(df.assertExpr()));
                }
                if (df.readonly()) {
                    sb.append(" READONLY");
                }
                if (df.valueExpr() != null) {
                    sb.append(" VALUE ").append(expr(df.valueExpr()));
                }
                if (df.defaultExpr() != null) {
                    sb.append(" DEFAULT ").append(expr(df.defaultExpr()));
                }
                if (df.references() != null) {
                    sb.append(" REFERENCES ").append(id(df.references()));
                }
                if (df.embeddingName() != null) {
                    sb.append(" EMBEDDING ").append(id(df.embeddingName())).append(" FROM ")
                        .append(path(df.embeddingFrom()));
                }
            }
            case Statement.DefineEmbedding de -> {
                sb.append("DEFINE EMBEDDING ").append(id(de.name())).append(" PROVIDER ").append(id(de.provider()))
                    .append(" URL \"").append(escape(de.url())).append("\" MODEL \"").append(escape(de.model()))
                    .append("\" DIMENSION ").append(de.dimension());
                if (de.keyEnv() != null) {
                    sb.append(" KEY ENV \"").append(escape(de.keyEnv())).append('"');
                }
                if (de.timeoutSeconds() != null) {
                    sb.append(" TIMEOUT ").append(de.timeoutSeconds());
                }
                if (de.batch() != null) {
                    sb.append(" BATCH ").append(de.batch());
                }
                if (de.documentPrefix() != null) {
                    sb.append(" DOCUMENT PREFIX \"").append(escape(de.documentPrefix())).append('"');
                }
                if (de.queryPrefix() != null) {
                    sb.append(" QUERY PREFIX \"").append(escape(de.queryPrefix())).append('"');
                }
                if (de.sendDimension()) {
                    sb.append(" SEND DIMENSION");
                }
                if (de.authHeader() != null) {
                    sb.append(" AUTH HEADER \"").append(escape(de.authHeader())).append('"');
                }
            }
            case Statement.DropEmbedding de -> sb.append("DROP EMBEDDING ").append(id(de.name()));
            case Statement.CreateFunction f -> program(sb, "FUNCTION", f.orReplace(), f.name(), f.params(), f.returns(),
                f.definer(), f.body(), true);
            case Statement.CreateProcedure f -> program(sb, "PROCEDURE", f.orReplace(), f.name(), f.params(),
                f.returns(), f.definer(), f.body(), true);
            case Statement.CreatePackage f -> program(sb, "PACKAGE", f.orReplace(), f.name(), List.of(), null,
                f.definer(), f.body(), false);
            case Statement.DropProgram d -> sb.append("DROP ").append(d.kind()).append(' ').append(id(d.name()));
            case Statement.CallProgram c -> {
                sb.append("CALL ").append(callName(c.name())).append('(');
                for (int i = 0; i < c.args().size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    sb.append(expr(c.args().get(i)));
                }
                sb.append(')');
            }
            case Statement.Reembed r -> {
                sb.append("REEMBED TABLE ").append(id(r.table()));
                if (r.field() != null) {
                    sb.append(" FIELD ").append(path(r.field()));
                }
                if (r.dryRun()) {
                    sb.append(" DRY RUN");
                }
            }
            case Statement.DefineIndex di -> {
                sb.append("DEFINE INDEX ").append(id(di.name())).append(" ON TABLE ").append(id(di.table()))
                    .append(" COLUMNS ").append(paths(di.columns()));
                if (di.unique()) {
                    sb.append(" UNIQUE");
                } else if (di.count()) {
                    sb.append(" COUNT");
                } else if (di.searchAnalyzer() != null) {
                    sb.append(" SEARCH ANALYZER ").append(id(di.searchAnalyzer()));
                } else if (di.geo()) {
                    sb.append(" GEO");
                } else if (di.columnar()) {
                    sb.append(" COLUMNAR");
                } else if (di.vectorDimension() != null) {
                    sb.append(" HNSW DIMENSION ").append(di.vectorDimension())
                        .append(" DIST ").append(id(di.vectorDistance()));
                    if (di.m() != null) {
                        sb.append(" M ").append(di.m());
                    }
                    if (di.efConstruction() != null) {
                        sb.append(" EFC ").append(di.efConstruction());
                    }
                    if (di.efSearch() != null) {
                        sb.append(" EFS ").append(di.efSearch());
                    }
                }
            }
            case Statement.DefineAnalyzer da -> {
                sb.append("DEFINE ANALYZER ").append(id(da.name()));
                if (da.lowercase()) {
                    sb.append(" LOWERCASE");
                }
                if (!da.stopwords().isEmpty()) {
                    sb.append(" STOPWORDS ");
                    for (int i = 0; i < da.stopwords().size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append('"').append(escape(da.stopwords().get(i))).append('"');
                    }
                }
                if (da.stemming()) {
                    sb.append(" STEMMING");
                }
            }
            case Statement.DefineEvent de -> {
                sb.append("DEFINE EVENT ").append(id(de.name())).append(" ON TABLE ").append(id(de.table()))
                    .append(" WHEN ").append(expr(de.when())).append(" THEN (");
                for (int i = 0; i < de.then().size(); i++) {
                    if (i > 0) {
                        sb.append("; ");
                    }
                    statement(sb, de.then().get(i));
                }
                sb.append(')');
            }
            case Statement.DefineUser du -> {
                sb.append("DEFINE USER ");
                if (du.name().matches("[\\p{L}_][\\p{L}\\p{N}_!]*") && !changesCase(du.name())) {
                    sb.append(du.name());
                } else {
                    sb.append('"').append(escape(du.name())).append('"');
                }
                sb.append(" ON ");
                authScope(sb, du.scope(), du.namespace(), du.database());
                if (du.certificateBased()) {
                    sb.append(" CERTIFICATE ").append(id(du.certificate()));
                    if (du.fingerprint() != null) {
                        sb.append(" FINGERPRINT \"").append(escape(du.fingerprint())).append('"');
                    }
                } else if (du.hashed()) {
                    sb.append(" PASSHASH \"").append(escape(du.passhash())).append('"');
                } else {
                    sb.append(" PASSWORD ").append(expr(du.password()));
                }
                if (!du.roles().isEmpty()) {
                    sb.append(" ROLES ").append(names(du.roles()));
                }
                if (!du.dataRules().isEmpty()) {
                    sb.append(" APPLY DATA RULE ").append(names(du.dataRules()));
                }
                if (du.auditName() != null && !du.auditName().isBlank()) {
                    sb.append(" AUDITED BY ").append(id(du.auditName()));
                }
            }
            case Statement.DefineAccess da -> {
                sb.append("DEFINE ACCESS ").append(id(da.name())).append(" ON ");
                authScope(sb, da.scope(), da.namespace(), da.database());
            }
            case Statement.Info inf -> {
                sb.append("INFO FOR ").append(inf.kind().toUpperCase());
                if (inf.table() != null) {
                    sb.append(' ').append(id(inf.table()));
                }
            }
            case Statement.Return r -> sb.append("RETURN ").append(expr(r.value()));
            case Statement.IfElse ie -> {
                for (int i = 0; i < ie.branches().size(); i++) {
                    var b = ie.branches().get(i);
                    sb.append(i == 0 ? "IF " : " ELSE IF ").append(expr(b.cond())).append(" THEN ")
                        .append(expr(b.thenExpr()));
                }
                if (ie.elseBranch() != null) {
                    sb.append(" ELSE ").append(expr(ie.elseBranch()));
                }
                sb.append(" END");
            }
            case Statement.Kill k -> sb.append("KILL ").append(expr(k.target()));
            case Statement.ErrorStmt e -> sb.append("ERROR ").append(expr(e.message()));
            case Statement.Begin ignored -> sb.append("BEGIN");
            case Statement.Commit ignored -> sb.append("COMMIT");
            case Statement.Cancel ignored -> sb.append("CANCEL");
            case Statement.Savepoint sp -> sb.append("SAVEPOINT ").append(id(sp.name()));
            case Statement.Release rl -> sb.append("RELEASE ").append(id(rl.name()));
            case Statement.RollbackTo rb -> sb.append("ROLLBACK TO ").append(id(rb.name()));
            case Statement.Join j -> throw new IllegalArgumentException(Messages.get("parser_join_outside_select"));
            case Statement.DefineDatabaseLink ddl -> {
                sb.append("DEFINE DATABASE LINK \"").append(escape(ddl.name())).append('"')
                    .append(" CONNECT BY \"").append(escape(ddl.url())).append('"')
                    .append(" WITH ns = \"").append(escape(ddl.ns())).append('"')
                    .append(" db = \"").append(escape(ddl.db())).append('"')
                    .append(" user = \"").append(escape(ddl.user())).append('"')
                    .append(" password = \"").append(escape(ddl.password())).append('"');
            }
            case Statement.DropDatabaseLink ddl2 -> {
                sb.append("DROP DATABASE LINK \"").append(escape(ddl2.name())).append('"');
            }
            case Statement.CreateSaga cs -> {
                sb.append("CREATE SAGA ").append(sagaName(cs.name())).append(" WITH DATABASES ");
                for (int i = 0; i < cs.links().size(); i++) {
                    if (i > 0) sb.append(", ");
                    String link = cs.links().get(i);
                    char quote = link.indexOf('\'') < 0 ? '\'' : '"';
                    sb.append(quote).append(escape(link)).append(quote);
                }
            }
            case Statement.DescribeSaga ds -> sb.append("DESCRIBE SAGA ").append(sagaName(ds.name()));
            case Statement.DescribeTable dt -> sb.append("DESCRIBE ").append(id(dt.table()));
            case Statement.ShowSagaTransaction st -> {
                sb.append("SHOW SAGA TRANSACTION ").append(sagaName(st.name()))
                    .append(" '").append(single(st.correlationId())).append('\'');
            }
            case Statement.BeginSaga bs -> {
                sb.append("BEGIN SAGA ").append(sagaName(bs.name()))
                    .append(" WITH CORRELATION '").append(single(bs.correlationId())).append('\'');
            }
            case Statement.CommitSaga cs2 -> {
                sb.append("COMMIT SAGA ").append(sagaName(cs2.name()))
                    .append(" WITH CORRELATION '").append(single(cs2.correlationId())).append('\'');
            }
            case Statement.CancelSaga cs3 -> {
                sb.append("CANCEL SAGA ").append(sagaName(cs3.name()))
                    .append(" WITH CORRELATION '").append(single(cs3.correlationId())).append('\'');
            }
            case Statement.JoinSaga js -> {
                sb.append("JOIN SAGA ").append(sagaName(js.name()))
                    .append(" WITH CORRELATION '").append(single(js.correlationId())).append('\'');
            }
            case Statement.LeaveSaga ignored -> {
                sb.append("LEAVE SAGA");
            }
            case Statement.CreateDataRule cd -> {
                sb.append("CREATE DATA RULE ").append(id(cd.name())).append(" APPLY ")
                    .append(expr(cd.predicate()));
                if (!cd.maskPatterns().isEmpty()) {
                    sb.append(" MASK FIELDS WITH");
                    for (int i = 0; i < cd.maskPatterns().size(); i++) {
                        sb.append(i == 0 ? " " : ", ");
                        sb.append('\'').append(single(cd.maskPatterns().get(i))).append('\'');
                    }
                }
            }
            case Statement.DropDataRule dr -> {
                sb.append("DROP DATA RULE ").append(id(dr.name()));
            }
            case Statement.ShowDataRules ignored -> {
                sb.append("SHOW DATA RULES");
            }
            case Statement.DefineAiAudit aa -> {
                sb.append("CREATE AI AUDIT ").append(id(aa.name()))
                    .append(" SET WARNING WHEN '").append(single(aa.warningWhen()))
                    .append("' SET DANGER WHEN '").append(single(aa.dangerWhen())).append("'");
            }
            case Statement.DropAiAudit da -> {
                sb.append("DROP AI AUDIT ").append(id(da.name()));
            }
            case Statement.ShowAiAudit sa -> {
                sb.append("SHOW AI AUDIT ").append(id(sa.name()));
            }
            case Statement.Partition p -> PartitionRender.render(sb, p.command());
            case Statement.RemoveTable rt -> {
                sb.append("REMOVE TABLE ").append(id(rt.name()));
            }
            case Statement.DropTable dt -> {
                sb.append("DROP TABLE ").append(id(dt.name()));
            }
            case Statement.AlterTable at -> {
                sb.append("ALTER TABLE ").append(id(at.name()));
                for (var op : at.operations()) {
                    switch (op) {
                        case Statement.AddColumn ac -> {
                            sb.append(" ADD COLUMN ").append(id(ac.name())).append(' ')
                                .append(typeText(ac.type().toUpperCase()));
                            if (ac.primaryKey()) sb.append(" PRIMARY KEY");
                            if (ac.notNull()) sb.append(" NOT NULL");
                            if (ac.defaultExpr() != null) sb.append(" DEFAULT ").append(expr(ac.defaultExpr()));
                            if (ac.checkExpr() != null) sb.append(" CHECK (").append(expr(ac.checkExpr())).append(')');
                            if (ac.references() != null) sb.append(" REFERENCES ").append(id(ac.references()));
                        }
                        case Statement.DropColumn dc -> sb.append(" DROP COLUMN ").append(id(dc.name()));
                        case Statement.ModifyColumn mc -> {
                            sb.append(" MODIFY COLUMN ").append(id(mc.name())).append(' ')
                                .append(typeText(mc.type().toUpperCase()));
                            if (mc.notNull()) sb.append(" NOT NULL");
                            if (mc.defaultExpr() != null) sb.append(" DEFAULT ").append(expr(mc.defaultExpr()));
                            if (mc.checkExpr() != null) sb.append(" CHECK (").append(expr(mc.checkExpr())).append(')');
                        }
                    }
                }
            }
            case Statement.CreateIndex ci -> {
                sb.append("CREATE INDEX ").append(id(ci.name())).append(" ON TABLE ").append(id(ci.table()))
                    .append(" COLUMNS ").append(paths(ci.columns()));
                if (ci.unique()) { sb.append(" UNIQUE"); }
                else if (ci.count()) { sb.append(" COUNT"); }
                else if (ci.searchAnalyzer() != null) { sb.append(" SEARCH ANALYZER ").append(id(ci.searchAnalyzer())); }
                else if (ci.geo()) { sb.append(" GEO"); }
                else if (ci.columnar()) { sb.append(" COLUMNAR"); }
                else if (ci.vectorDimension() != null) {
                    sb.append(" HNSW DIMENSION ").append(ci.vectorDimension()).append(" DIST ").append(id(ci.vectorDistance()));
                    if (ci.m() != null) sb.append(" M ").append(ci.m());
                    if (ci.efConstruction() != null) sb.append(" EFC ").append(ci.efConstruction());
                    if (ci.efSearch() != null) sb.append(" EFS ").append(ci.efSearch());
                }
            }
            case Statement.DropIndex di -> {
                sb.append("DROP INDEX ").append(id(di.name()));
                if (di.table() != null) {
                    sb.append(" ON TABLE ").append(id(di.table()));
                }
            }
            case Statement.CreateEvent ce -> {
                sb.append("CREATE EVENT ").append(id(ce.name())).append(" ON TABLE ").append(id(ce.table()))
                    .append(" WHEN ").append(expr(ce.when())).append(" THEN (");
                for (int i = 0; i < ce.then().size(); i++) {
                    if (i > 0) sb.append("; ");
                    statement(sb, ce.then().get(i));
                }
                sb.append(')');
            }
            case Statement.DropEvent de -> sb.append("DROP EVENT ").append(id(de.name()));
            case Statement.CreateAnalyzer ca -> {
                // CREATE ANALYZER sem opção alguma seria lido como a criação de um registro
                boolean bare = !ca.lowercase() && !ca.stemming() && ca.stopwords().isEmpty();
                sb.append(bare ? "ALTER ANALYZER " : "CREATE ANALYZER ").append(id(ca.name()));
                if (ca.lowercase()) sb.append(" LOWERCASE");
                if (!ca.stopwords().isEmpty()) {
                    sb.append(" STOPWORDS ");
                    for (int i = 0; i < ca.stopwords().size(); i++) {
                        if (i > 0) sb.append(", ");
                        sb.append('"').append(escape(ca.stopwords().get(i))).append('"');
                    }
                }
                if (ca.stemming()) sb.append(" STEMMING");
            }
            case Statement.DropAnalyzer da -> sb.append("DROP ANALYZER ").append(id(da.name()));
            case Statement.CreateUser cu -> {
                sb.append("CREATE USER ");
                if (cu.name().matches("[\\p{L}_][\\p{L}\\p{N}_!]*") && !changesCase(cu.name())) {
                    sb.append(cu.name());
                } else {
                    sb.append('"').append(escape(cu.name())).append('"');
                }
                sb.append(" ON ");
                authScope(sb, cu.scope(), cu.namespace(), cu.database());
                if (cu.certificateBased()) {
                    sb.append(" CERTIFICATE ").append(id(cu.certificate()));
                    if (cu.fingerprint() != null) {
                        sb.append(" FINGERPRINT \"").append(escape(cu.fingerprint())).append('"');
                    }
                } else if (cu.hashed()) {
                    sb.append(" PASSHASH \"").append(escape(cu.passhash())).append('"');
                } else {
                    sb.append(" PASSWORD ").append(expr(cu.password()));
                }
                if (!cu.roles().isEmpty()) {
                    sb.append(" ROLES ").append(names(cu.roles()));
                }
                if (!cu.dataRules().isEmpty()) {
                    sb.append(" APPLY DATA RULE ").append(names(cu.dataRules()));
                }
                if (cu.auditName() != null && !cu.auditName().isBlank()) {
                    sb.append(" AUDITED BY ").append(id(cu.auditName()));
                }
            }
            case Statement.DropUser du -> sb.append("DROP USER ").append(id(du.name()));
            case Statement.GrantAccess ga -> {
                sb.append("GRANT ACCESS ").append(id(ga.name())).append(" ON ");
                authScope(sb, ga.scope(), ga.namespace(), ga.database());
            }
            case Statement.RevokeAccess ra -> sb.append("REVOKE ACCESS ").append(id(ra.name()));
            case Statement.CreateDatabaseLink cdl -> {
                sb.append("CREATE DATABASE LINK \"").append(escape(cdl.name())).append('"')
                    .append(" CONNECT BY \"").append(escape(cdl.url())).append('"')
                    .append(" WITH ns = \"").append(escape(cdl.ns())).append('"')
                    .append(" db = \"").append(escape(cdl.db())).append('"')
                    .append(" user = \"").append(escape(cdl.user())).append('"')
                    .append(" password = \"").append(escape(cdl.password())).append('"');
            }
            case Statement.AlterDatabaseLink adl -> {
                sb.append("ALTER DATABASE LINK \"").append(escape(adl.name())).append('"')
                    .append(" CONNECT BY \"").append(escape(adl.url())).append('"')
                    .append(" WITH ns = \"").append(escape(adl.ns())).append('"')
                    .append(" db = \"").append(escape(adl.db())).append('"')
                    .append(" user = \"").append(escape(adl.user())).append('"')
                    .append(" password = \"").append(escape(adl.password())).append('"');
            }
            case Statement.DropSaga ds -> sb.append("DROP SAGA ").append(sagaName(ds.name()));
            case Statement.SetReasonAudit sra -> {
                sb.append("SET REASON AUDIT CASE '").append(single(sra.hash()))
                    .append("' '").append(single(sra.reason())).append("'");
            }
            case Statement.SetAuditCase sac -> {
                sb.append("SET AUDIT CASE '").append(single(sac.hash()))
                    .append("' ").append(sac.status()).append(" REASON '")
                    .append(single(sac.reason())).append("'");
            }
        }
    }

    private static void authScope(StringBuilder sb, Statement.AuthScope scope, String ns, String db) {
        switch (scope) {
            case ROOT -> sb.append("ROOT");
            case NAMESPACE -> sb.append("NAMESPACE").append(ns == null ? "" : " " + scopeName(ns));
            case DATABASE -> {
                sb.append("DATABASE");
                if (ns != null) {
                    sb.append(" ").append(scopeName(ns));
                }
                if (db != null) {
                    sb.append(" ").append(scopeName(db));
                }
            }
        }
    }

    private static void renderPermissions(StringBuilder sb, Statement.Permissions perms) {
        if (perms == null || allNull(perms)) {
            return;
        }
        sb.append(" PERMISSIONS");
        permPart(sb, "select", perms.select());
        permPart(sb, "create", perms.create());
        permPart(sb, "update", perms.update());
        permPart(sb, "delete", perms.delete());
    }

    private static void permPart(StringBuilder sb, String action, com.axonbase.parser.ast.Expr cond) {
        if (cond == null) {
            return;
        }
        sb.append(" FOR ").append(action).append(" WHERE ").append(expr(cond));
    }

    private static boolean allNull(Statement.Permissions p) {
        return p.select() == null && p.create() == null && p.update() == null && p.delete() == null;
    }

    private static void data(StringBuilder sb, Statement.Data data) {
        if (data == null) {
            return;
        }
        switch (data) {
            case Statement.Data.Content c -> sb.append(" CONTENT ").append(expr(c.content()));
            case Statement.Data.SetClause sc -> {
                sb.append(" SET ");
                for (int i = 0; i < sc.assignments().size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    var a = sc.assignments().get(i);
                    sb.append(expr(a.path())).append(" = ").append(expr(a.value()));
                }
            }
            case Statement.Data.Merge m -> sb.append(" MERGE ").append(expr(m.merge()));
            case Statement.Data.Patch p -> sb.append(" PATCH ").append(expr(p.patch()));
            case Statement.Data.Replace r -> sb.append(" REPLACE ").append(expr(r.replace()));
        }
    }

    private static void ret(StringBuilder sb, Statement.ReturnSpec ret) {
        if (ret == null || ret.kind() == Statement.ReturnKind.AFTER) {
            return;
        }
        sb.append(" RETURN ");
        switch (ret.kind()) {
            case BEFORE -> sb.append("BEFORE");
            case NONE -> sb.append("NONE");
            case DIFF -> sb.append("DIFF");
            case EXPR -> sb.append(expr(ret.expr()));
            default -> {
            }
        }
    }

    private static void program(StringBuilder sb, String kind, boolean orReplace, String name,
                                List<Statement.ProgramParam> params, String returns, boolean definer, String body,
                                boolean withParams) {
        sb.append("CREATE ").append(orReplace ? "OR REPLACE " : "").append(kind).append(' ').append(id(name));
        if (withParams) {
            sb.append('(');
            for (int i = 0; i < params.size(); i++) {
                Statement.ProgramParam p = params.get(i);
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(id(p.name()));
                if (p.type() != null) {
                    sb.append(' ').append(typeText(p.type()));
                }
                if (p.defaultExpr() != null) {
                    sb.append(" = ").append(expr(p.defaultExpr()));
                }
            }
            sb.append(')');
        }
        if (returns != null) {
            sb.append(" RETURNS ").append(typeText(returns));
        }
        if (definer) {
            sb.append(" SECURITY DEFINER");
        }
        sb.append(" AS ").append(ProgramText.quote(body));
    }

    public static String expr(Expr e) {
        StringBuilder sb = new StringBuilder();
        expr(sb, e);
        return sb.toString();
    }

    private static void expr(StringBuilder sb, Expr e) {
        switch (e) {
            case Expr.Literal l -> literal(sb, l.value());
            case Expr.Ident id -> {
                sb.append("*".equals(id.name()) ? "*" : id(id.name()));
            }
            case Expr.Qualified q -> sb.append(id(q.link())).append('.').append(id(q.table()));
            case Expr.Param p -> sb.append('$').append(p.name());
            case Expr.Path p -> sb.append(callName(p.name()));
            case Expr.Call c2 -> {
                boolean distinct = c2.name().equals(Expr.COUNT_DISTINCT);
                sb.append(distinct ? "count" : callName(c2.name())).append('(');
                if (distinct) {
                    sb.append("DISTINCT ");
                }
                for (int i = 0; i < c2.args().size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    expr(sb, c2.args().get(i));
                }
                sb.append(')');
            }
            case Expr.RecordId r -> {
                expr(sb, r.table());
                sb.append(':');
                renderRecordKey(sb, r.key());
            }
            case Expr.ObjectLit o -> object(sb, o.entries());
            case Expr.ArrayLit a -> array(sb, a.items());
            case Expr.SetLit s2 -> {
                sb.append('{');
                for (int i = 0; i < s2.items().size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    // no começo, "{t:1, ..." seria lido como objeto
                    Expr item = s2.items().get(i);
                    sb.append(grouped(item, i == 0 && startsWithRecordId(item)));
                }
                sb.append('}');
            }
            case Expr.Block b -> {
                sb.append('{');
                for (int i = 0; i < b.statements().size(); i++) {
                    if (i > 0) {
                        sb.append("; ");
                    }
                    statement(sb, b.statements().get(i));
                }
                sb.append('}');
            }
            case Expr.Unary u -> {
                switch (u.op()) {
                    case NOT -> sb.append('!');
                    case NEG -> sb.append('-');
                    case POS -> sb.append('+');
                }
                String operand = grouped(u.operand(), precedence(u.operand()) < PREFIX_PRECEDENCE);
                // "--" abre comentário
                boolean comment = u.op() == Expr.UnaryOp.NEG && operand.startsWith("-");
                sb.append(comment ? "(" + operand + ")" : operand);
            }
            case Expr.Binary b -> {
                // os parênteses não ficam na árvore: voltam pela precedência, senão (a OR b) AND c muda de sentido
                int own = precedence(b.op());
                boolean rightAssociative = b.op() == Expr.BinaryOp.POW;
                int left = precedence(b.left());
                int right = precedence(b.right());
                sb.append(grouped(b.left(), rightAssociative ? left <= own : left < own));
                sb.append(' ').append(binOp(b.op())).append(' ');
                sb.append(grouped(b.right(), rightAssociative ? right < own : right <= own));
            }
            case Expr.Cast c2 -> sb.append('<').append(id(c2.type())).append("> ")
                .append(grouped(c2.operand(), precedence(c2.operand()) < PREFIX_PRECEDENCE));
            case Expr.Idiom i -> {
                // travessia que começa na seta ({@code ->segue->pessoa}) tem uma base sintética "*" que não se
                // escreve: "*->segue" releria como "todos os campos" seguido de uma seta solta
                boolean syntheticBase = i.base() instanceof Expr.Ident id && id.name().equals("*")
                    && !i.parts().isEmpty() && i.parts().get(0) instanceof Expr.Part.Graph;
                if (!syntheticBase) {
                    sb.append(grouped(i.base(), precedence(i.base()) < ATOM_PRECEDENCE));
                }
                for (var part : i.parts()) {
                    idiomPart(sb, part);
                }
            }
            case Expr.Range r -> {
                if (r.start() != null) {
                    sb.append(grouped(r.start(), precedence(r.start()) < RANGE_PRECEDENCE));
                }
                sb.append(r.skipStart() ? ">.." : r.inclusive() ? ".." : "..");
                if (r.end() != null) {
                    sb.append(grouped(r.end(), precedence(r.end()) <= RANGE_PRECEDENCE));
                }
            }
            case Expr.SubQuery q -> {
                sb.append('(').append(query(q.query())).append(')');
            }
            case Expr.Alias a -> {
                expr(sb, a.expr());
                sb.append(" AS ").append(id(a.name()));
            }
        }
    }

    private static void renderRecordKey(StringBuilder sb, Expr key) {
        if (key instanceof Expr.Literal lit && lit.value().isString()) {
            // um texto como "7" fica entre aspas, senão voltaria como número
            String text = lit.value().asString();
            boolean bare = text.matches("[\\p{L}_][\\p{L}\\p{Nd}_]*")
                && !AxonQlText.KEYWORDS.contains(text.toLowerCase(java.util.Locale.ROOT));
            sb.append(bare ? text : AxonQlText.string(text));
            return;
        }
        if (key instanceof Expr.Literal lit && lit.value().isNumber()) {
            sb.append(numberKey(lit.value()));
            return;
        }
        expr(sb, key);
    }

    private static void object(StringBuilder sb, List<Expr.ObjectLit.Entry> entries) {
        sb.append('{');
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            var en = entries.get(i);
            sb.append(objectKey(en.key())).append(": ");
            expr(sb, en.value());
        }
        sb.append('}');
    }

    private static void array(StringBuilder sb, List<Expr> items) {
        sb.append('[');
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            expr(sb, items.get(i));
        }
        sb.append(']');
    }

    private static void idiomPart(StringBuilder sb, Expr.Part p) {
        switch (p) {
            case Expr.Part.Field f -> sb.append('.').append(id(f.name()));
            case Expr.Part.All ignored -> sb.append(".*");
            case Expr.Part.First ignored -> sb.append(".?");
            case Expr.Part.Last ignored -> sb.append(".?:");
            case Expr.Part.Index ix -> {
                sb.append('[');
                expr(sb, ix.index());
                sb.append(']');
            }
            case Expr.Part.Where w -> {
                sb.append("[WHERE ");
                expr(sb, w.cond());
                sb.append(']');
            }
            case Expr.Part.Method m -> {
                sb.append('.').append(id(m.name())).append('(');
                for (int i = 0; i < m.args().size(); i++) {
                    if (i > 0) {
                        sb.append(", ");
                    }
                    expr(sb, m.args().get(i));
                }
                sb.append(')');
            }
            case Expr.Part.Graph g -> {
                sb.append(g.direction() == Expr.Direction.IN ? "<-" : "->");
                graphLookup(sb, g.lookup());
            }
        }
    }

    /**
     * Alvo de um passo de travessia. O nome qualificado de um DATABASE LINK sai entre aspas ({@code "link"."tabela"}),
     * porque sem aspas {@code tabela.campo} numa travessia é acesso a campo ({@code ->comprou->produto.nome}).
     */
    private static void graphLookup(StringBuilder sb, Expr lookup) {
        if (lookup instanceof Expr.Qualified q) {
            sb.append('"').append(escape(q.link())).append("\".\"").append(escape(q.table())).append('"');
        } else if (lookup instanceof Expr.RecordId r && r.table() instanceof Expr.Qualified q) {
            graphLookup(sb, q);
            sb.append(':');
            renderRecordKey(sb, r.key());
        } else {
            expr(sb, lookup);
        }
    }

    private static String binOp(Expr.BinaryOp op) {
        return switch (op) {
            case OR -> "OR";
            case AND -> "AND";
            case EQ -> "=";
            case EQ_EXACT -> "==";
            case NE -> "!=";
            case LT -> "<";
            case GT -> ">";
            case LE -> "<=";
            case GE -> ">=";
            case ADD -> "+";
            case SUB -> "-";
            case MUL -> "*";
            case DIV -> "/";
            case MOD -> "%";
            case POW -> "**";
            case ALL_EQ -> "*=";
            case ANY_EQ -> "?=";
            case COALESCE -> "??";
            case TERNARY -> "?:";
            case CONTAIN -> "CONTAINS";
            case NOT_CONTAIN -> "NOT CONTAINS";
            case CONTAIN_ALL -> "CONTAINSALL";
            case CONTAIN_ANY -> "CONTAINSANY";
            case CONTAIN_NONE -> "CONTAINSNONE";
            case INSIDE -> "INSIDE";
            case NOT_INSIDE -> "NOT IN";
            case ALL_INSIDE -> "ALLINSIDE";
            case ANY_INSIDE -> "ANYINSIDE";
            case NONE_INSIDE -> "NONEINSIDE";
            case OUTSIDE -> "OUTSIDE";
            case INTERSECTS -> "INTERSECTS";
            case MATCH -> "@@";
        };
    }

    private static void literal(StringBuilder sb, AxonValue v) {
        switch (v.type()) {
            case STRING -> sb.append('"').append(escape(v.asString())).append('"');
            case TABLE -> sb.append(id(v.asTable()));
            case RECORD_ID -> sb.append(AxonQlText.recordId(v.asRecordId().table(), v.asRecordId().key()));
            default -> sb.append(value(v));
        }
    }

    /**
     * Literal AxonQL que, avaliado, devolve o mesmo valor com o mesmo tipo. É o que o dump lógico usa no conteúdo
     * dos registros: o JSON escreveria decimais, datas e outros tipos num objeto marcado, que a AxonQL lê como
     * objeto. A exceção é a referência a registro, que sai como o texto que o armazenamento guarda.
     */
    public static String value(AxonValue v) {
        return AxonQlText.storedValue(v);
    }

    /** Chave de um record id como texto que volta a ser lido na posição de chave. */
    public static String key(Expr key) {
        return key instanceof Expr.Literal lit && lit.value().isNumber() ? numberKey(lit.value()) : expr(key);
    }

    /** Ver {@link AxonQlText#numberKey(AxonValue)}. */
    public static String numberKey(AxonValue v) {
        return AxonQlText.numberKey(v);
    }

    /** Nome de SAGA como texto que volta a ser lido: fora do formato de identificador, vai entre aspas. */
    public static String sagaName(String name) {
        return name.matches("[A-Za-z_][A-Za-z0-9_]*") ? name : "\"" + escape(name) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /** Literal entre aspas simples: dentro de texto o escape é a barra invertida. */
    private static String single(String s) {
        return s.replace("\\", "\\\\").replace("'", "\\'");
    }

    /** Identificador como a AxonQL o relê. */
    private static String id(String name) {
        return AxonQlText.identifier(name);
    }

    /** Palavra-chave com maiúsculas: sem aspas ou crases o lexer a devolveria em minúsculas. */
    private static boolean changesCase(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        return !lower.equals(name) && AxonQlText.KEYWORDS.contains(lower);
    }

    private static String useName(Expr name) {
        return name instanceof Expr.Ident ident ? scopeName(ident.name()) : expr(name);
    }

    private static String createTarget(Expr target) {
        if (target instanceof Expr.Ident table && AFTER_CREATE.contains(table.name())) {
            return "`" + table.name() + "`";
        }
        if (target instanceof Expr.RecordId record && record.table() instanceof Expr.Ident table
            && AFTER_CREATE.contains(table.name())) {
            StringBuilder sb = new StringBuilder("`").append(table.name()).append("`:");
            renderRecordKey(sb, record.key());
            return sb.toString();
        }
        return expr(target);
    }

    /** {@code SELECT value} é o modificador VALUE: o campo com esse nome leva crases. */
    private static String firstField(Expr field) {
        if (field instanceof Expr.Ident ident && ident.name().equalsIgnoreCase("value")) {
            return "`" + ident.name() + "`";
        }
        if (field instanceof Expr.Alias alias && alias.expr() instanceof Expr.Ident ident
            && ident.name().equalsIgnoreCase("value")) {
            return "`" + ident.name() + "` AS " + id(alias.name());
        }
        return expr(field);
    }

    private static boolean startsWithRecordId(Expr item) {
        return item instanceof Expr.RecordId
            || item instanceof Expr.Idiom idiom && idiom.base() instanceof Expr.RecordId;
    }

    /** Namespace ou banco numa cláusula ON: ali o parser só aceita identificador, então palavra-chave leva crases. */
    private static String scopeName(String name) {
        return AxonQlText.KEYWORDS.contains(name.toLowerCase(java.util.Locale.ROOT)) ? "`" + name + "`" : id(name);
    }

    private static String path(String dotted) {
        return AxonQlText.path(dotted);
    }

    private static String names(List<String> names) {
        return names.stream().map(Render::id).collect(Collectors.joining(", "));
    }

    private static String paths(List<String> paths) {
        return paths.stream().map(Render::path).collect(Collectors.joining(", "));
    }

    /** Nome de função com namespace ({@code a::b}). */
    private static String callName(String name) {
        String[] segments = name.split("::", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < segments.length; i++) {
            if (i > 0) {
                sb.append("::");
            }
            String segment = segments[i];
            boolean bare = segment.matches("[\\p{L}_][\\p{L}\\p{Nd}_!]*")
                && !(i == 0 && NOT_CALLABLE.contains(segment.toLowerCase(java.util.Locale.ROOT)));
            sb.append(bare ? segment : id(segment));
        }
        return sb.toString();
    }

    /** Tipo ({@code int}, {@code array<string>}); fora desse formato é citado como um nome. */
    private static String typeText(String type) {
        return type.matches("[\\p{L}_][\\p{L}\\p{Nd}_]*(<[\\p{L}\\p{Nd}_,<> ]*>)?") ? type : id(type);
    }

    /** Chave de objeto: no começo de um objeto, uma palavra de comando abriria um bloco. */
    private static String objectKey(String key) {
        boolean bare = key.matches("[\\p{L}_][\\p{L}\\p{Nd}_]*")
            && !STATEMENT_WORDS.contains(key.toLowerCase(java.util.Locale.ROOT)) && !changesCase(key);
        return bare ? key : AxonQlText.string(key);
    }

    private static String grouped(Expr e, boolean parentheses) {
        return parentheses ? "(" + expr(e) + ")" : expr(e);
    }

    /** A mesma tabela de precedência do {@link Parser}. */
    private static int precedence(Expr.BinaryOp op) {
        return switch (op) {
            case COALESCE, TERNARY -> 1;
            case OR -> 2;
            case AND -> 3;
            case EQ, EQ_EXACT, NE, ALL_EQ, ANY_EQ -> 4;
            case LT, GT, LE, GE, MATCH, CONTAIN, NOT_CONTAIN, CONTAIN_ALL, CONTAIN_ANY, CONTAIN_NONE, INSIDE,
                 NOT_INSIDE, ALL_INSIDE, ANY_INSIDE, NONE_INSIDE, OUTSIDE, INTERSECTS -> RANGE_PRECEDENCE;
            case ADD, SUB -> 6;
            case MUL, DIV, MOD -> 7;
            case POW -> 8;
        };
    }

    private static int precedence(Expr e) {
        return switch (e) {
            case Expr.Binary b -> precedence(b.op());
            case Expr.Range ignored -> RANGE_PRECEDENCE;
            case Expr.Alias ignored -> 0;
            case Expr.Unary ignored -> PREFIX_PRECEDENCE;
            case Expr.Cast ignored -> PREFIX_PRECEDENCE;
            default -> ATOM_PRECEDENCE;
        };
    }
}
