package com.axonbase.parser;

import com.axonbase.common.Messages;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Query;
import com.axonbase.parser.ast.Statement;
import com.axonbase.parser.ast.Statement.ReturnSpec;

import java.util.List;

/** Renderizador canônico da AxonQL, usado nos testes de round-trip. */
public final class Render {

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
                sb.append("USE ");
                if (u.ns() != null) {
                    sb.append("NS ").append(expr(u.ns()));
                }
                if (u.db() != null) {
                    if (u.ns() != null) {
                        sb.append(' ');
                    }
                    sb.append("DB ").append(expr(u.db()));
                }
            }
            case Statement.Let l -> sb.append("LET $").append(l.name()).append(" = ").append(expr(l.value()));
            case Statement.Create c -> {
                sb.append("CREATE ");
                if (c.only()) {
                    sb.append("ONLY ");
                }
                sb.append(expr(c.target()));
                data(sb, c.data());
                ret(sb, c.ret());
            }
            case Statement.CreateTable ct -> {
                sb.append("CREATE TABLE ");
                if (ct.ifNotExists()) {
                    sb.append("IF NOT EXISTS ");
                }
                sb.append(ct.name()).append(" (");
                for (int i = 0; i < ct.columns().size(); i++) {
                    if (i > 0) sb.append(", ");
                    Statement.ColumnDef col = ct.columns().get(i);
                    sb.append(col.name()).append(' ').append(col.type().toUpperCase());
                    if (col.primaryKey()) sb.append(" PRIMARY KEY");
                    if (col.notNull()) sb.append(" NOT NULL");
                    if (col.defaultExpr() != null) sb.append(" DEFAULT ").append(expr(col.defaultExpr()));
                    if (col.checkExpr() != null) sb.append(" CHECK (").append(expr(col.checkExpr())).append(')');
                    if (col.references() != null) sb.append(" REFERENCES ").append(col.references());
                }
                sb.append(") WITH (SCHEMA = '").append(ct.schemafull() ? "FULL" : "SCHEMALESS").append("')");
            }
            case Statement.CreateJks jks -> {
                sb.append("CREATE JKS ").append(jks.name())
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
                sb.append("INTO ").append(i.table()).append(' ').append(expr(i.data()));
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
                    sb.append(expr(s2.fields().get(i)));
                }
                if (s2.only()) {
                    sb.append(" ONLY");
                }
                sb.append(" FROM ").append(expr(s2.from()));
                if (s2.timeTravel() != null) {
                    var travel = s2.timeTravel();
                    sb.append(travel.mode() == Statement.TimeTravelMode.AT ? " AT (" : " BEFORE (")
                        .append(travel.selector()).append(" => ").append(expr(travel.value())).append(')');
                }
                for (var join : s2.joins()) {
                    sb.append(" JOIN ").append(expr(join.source()))
                        .append(" ON ").append(expr(join.left()))
                        .append(" = ").append(expr(join.right()));
                }
                if (s2.cond() != null) {
                    sb.append(" WHERE ").append(expr(s2.cond()));
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
                if (!s2.group().isEmpty()) {
                    sb.append(" GROUP BY ");
                    for (int i = 0; i < s2.group().size(); i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(expr(s2.group().get(i)));
                    }
                }
                if (s2.limit() != null) {
                    sb.append(" LIMIT ").append(expr(s2.limit()));
                }
                if (s2.start() != null) {
                    sb.append(" START ").append(expr(s2.start()));
                }
                if (!s2.fetch().isEmpty()) {
                    sb.append(" FETCH ").append(String.join(", ", s2.fetch()));
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
            case Statement.DefineTable dt -> {
                sb.append("DEFINE TABLE ").append(dt.name());
                if (dt.schemafull()) {
                    sb.append(" SCHEMAFULL");
                } else {
                    sb.append(" SCHEMALESS");
                }
                if (dt.drop()) {
                    sb.append(" DROP");
                }
                renderPermissions(sb, dt.permissions());
            }
            case Statement.DefineField df -> {
                sb.append("DEFINE FIELD ").append(df.name()).append(" ON TABLE ").append(df.table());
                if (df.type() != null) {
                    sb.append(" TYPE ").append(df.type());
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
                    sb.append(" REFERENCES ").append(df.references());
                }
            }
            case Statement.DefineIndex di -> {
                sb.append("DEFINE INDEX ").append(di.name()).append(" ON TABLE ").append(di.table())
                    .append(" COLUMNS ").append(String.join(", ", di.columns()));
                if (di.unique()) {
                    sb.append(" UNIQUE");
                } else if (di.count()) {
                    sb.append(" COUNT");
                } else if (di.searchAnalyzer() != null) {
                    sb.append(" SEARCH ANALYZER ").append(di.searchAnalyzer());
                } else if (di.geo()) {
                    sb.append(" GEO");
                } else if (di.columnar()) {
                    sb.append(" COLUMNAR");
                } else if (di.vectorDimension() != null) {
                    sb.append(" HNSW DIMENSION ").append(di.vectorDimension())
                        .append(" DIST ").append(di.vectorDistance());
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
                sb.append("DEFINE ANALYZER ").append(da.name());
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
                sb.append("DEFINE EVENT ").append(de.name()).append(" ON TABLE ").append(de.table())
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
                if (du.name().matches("[\\p{L}_][\\p{L}\\p{N}_!]*")) {
                    sb.append(du.name());
                } else {
                    sb.append('"').append(escape(du.name())).append('"');
                }
                sb.append(" ON ");
                authScope(sb, du.scope(), du.namespace(), du.database());
                if (du.certificateBased()) {
                    sb.append(" CERTIFICATE ").append(du.certificate());
                    if (du.fingerprint() != null) {
                        sb.append(" FINGERPRINT \"").append(escape(du.fingerprint())).append('"');
                    }
                } else if (du.hashed()) {
                    sb.append(" PASSHASH \"").append(escape(du.passhash())).append('"');
                } else {
                    sb.append(" PASSWORD ").append(expr(du.password()));
                }
                if (!du.roles().isEmpty()) {
                    sb.append(" ROLES ").append(String.join(", ", du.roles()));
                }
                if (!du.dataRules().isEmpty()) {
                    sb.append(" APPLY DATA RULE ").append(String.join(", ", du.dataRules()));
                }
                if (du.auditName() != null && !du.auditName().isBlank()) {
                    sb.append(" AUDITED BY ").append(du.auditName());
                }
            }
            case Statement.DefineAccess da -> {
                sb.append("DEFINE ACCESS ").append(da.name()).append(" ON ");
                authScope(sb, da.scope(), da.namespace(), da.database());
            }
            case Statement.Info inf -> {
                sb.append("INFO FOR ").append(inf.kind().toUpperCase());
                if (inf.table() != null) {
                    sb.append(' ').append(inf.table());
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
            case Statement.Savepoint sp -> sb.append("SAVEPOINT ").append(sp.name());
            case Statement.Release rl -> sb.append("RELEASE ").append(rl.name());
            case Statement.RollbackTo rb -> sb.append("ROLLBACK TO ").append(rb.name());
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
                sb.append("CREATE SAGA ").append(cs.name()).append(" WITH DATABASES ");
                for (int i = 0; i < cs.links().size(); i++) {
                    if (i > 0) sb.append(", ");
                    sb.append('\'').append(escape(cs.links().get(i))).append('\'');
                }
            }
            case Statement.DescribeSaga ds -> sb.append("DESCRIBE SAGA ").append(ds.name());
            case Statement.DescribeTable dt -> sb.append("DESCRIBE ").append(dt.table());
            case Statement.ShowSagaTransaction st -> {
                sb.append("SHOW SAGA TRANSACTION ").append(st.name())
                    .append(" '").append(escape(st.correlationId())).append('\'');
            }
            case Statement.BeginSaga bs -> {
                sb.append("BEGIN SAGA ").append(bs.name())
                    .append(" WITH CORRELATION '").append(escape(bs.correlationId())).append('\'');
            }
            case Statement.CommitSaga cs2 -> {
                sb.append("COMMIT SAGA ").append(cs2.name())
                    .append(" WITH CORRELATION '").append(escape(cs2.correlationId())).append('\'');
            }
            case Statement.CancelSaga cs3 -> {
                sb.append("CANCEL SAGA ").append(cs3.name())
                    .append(" WITH CORRELATION '").append(escape(cs3.correlationId())).append('\'');
            }
            case Statement.CreateDataRule cd -> {
                sb.append("CREATE DATA RULE ").append(cd.name()).append(" APPLY ")
                    .append(expr(cd.predicate()));
                if (!cd.maskPatterns().isEmpty()) {
                    sb.append(" MASK FIELDS WITH");
                    for (int i = 0; i < cd.maskPatterns().size(); i++) {
                        sb.append(i == 0 ? " " : ", ");
                        sb.append('\'').append(escape(cd.maskPatterns().get(i))).append('\'');
                    }
                }
            }
            case Statement.DropDataRule dr -> {
                sb.append("DROP DATA RULE ").append(dr.name());
            }
            case Statement.ShowDataRules ignored -> {
                sb.append("SHOW DATA RULES");
            }
            case Statement.DefineAiAudit aa -> {
                sb.append("CREATE AI AUDIT ").append(aa.name())
                    .append(" SET WARNING WHEN '").append(escape(aa.warningWhen()))
                    .append("' SET DANGER WHEN '").append(escape(aa.dangerWhen())).append("'");
            }
            case Statement.DropAiAudit da -> {
                sb.append("DROP AI AUDIT ").append(da.name());
            }
            case Statement.RemoveTable rt -> {
                sb.append("REMOVE TABLE ").append(rt.name());
            }
            case Statement.ShowAiAudit sa -> {
                sb.append("SHOW AI AUDIT ").append(sa.name());
            }
            case Statement.SetReasonAudit sra -> {
                sb.append("SET REASON AUDIT CASE '").append(escape(sra.hash()))
                    .append("' '").append(escape(sra.reason())).append("'");
            }
            case Statement.SetAuditCase sac -> {
                sb.append("SET AUDIT CASE '").append(escape(sac.hash()))
                    .append("' ").append(sac.status()).append(" REASON '")
                    .append(escape(sac.reason())).append("'");
            }
        }
    }

    private static void authScope(StringBuilder sb, Statement.AuthScope scope, String ns, String db) {
        switch (scope) {
            case ROOT -> sb.append("ROOT");
            case NAMESPACE -> sb.append("NAMESPACE").append(ns == null ? "" : " " + ns);
            case DATABASE -> {
                sb.append("DATABASE");
                if (ns != null) {
                    sb.append(" ").append(ns);
                }
                if (db != null) {
                    sb.append(" ").append(db);
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

    public static String expr(Expr e) {
        StringBuilder sb = new StringBuilder();
        expr(sb, e);
        return sb.toString();
    }

    private static void expr(StringBuilder sb, Expr e) {
        switch (e) {
            case Expr.Literal l -> literal(sb, l.value());
            case Expr.Ident id -> {
                if (id.name() == "*") {
                    sb.append('*');
                } else {
                    sb.append(id.name());
                }
            }
            case Expr.Qualified q -> sb.append(q.link()).append('.').append(q.table());
            case Expr.Param p -> sb.append('$').append(p.name());
            case Expr.Path p -> sb.append(p.name());
            case Expr.Call c2 -> {
                sb.append(c2.name()).append('(');
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
                    expr(sb, s2.items().get(i));
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
                expr(sb, u.operand());
            }
            case Expr.Binary b -> {
                expr(sb, b.left());
                sb.append(' ').append(binOp(b.op())).append(' ');
                expr(sb, b.right());
            }
            case Expr.Cast c2 -> sb.append('<').append(c2.type()).append("> ").append(expr(c2.operand()));
            case Expr.Idiom i -> {
                expr(sb, i.base());
                for (var part : i.parts()) {
                    idiomPart(sb, part);
                }
            }
            case Expr.Range r -> {
                if (r.start() != null) {
                    expr(sb, r.start());
                }
                sb.append(r.skipStart() ? ">.." : r.inclusive() ? ".." : "..");
                if (r.end() != null) {
                    expr(sb, r.end());
                }
            }
            case Expr.SubQuery q -> {
                sb.append('(').append(query(q.query())).append(')');
            }
            case Expr.Alias a -> {
                expr(sb, a.expr());
                sb.append(" AS ").append(a.name());
            }
        }
    }

    private static void renderRecordKey(StringBuilder sb, Expr key) {
        if (key instanceof Expr.Literal lit && lit.value().isString()) {
            sb.append(lit.value().asString());
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
            sb.append(quoteIfNeeded(en.key())).append(": ");
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
            case Expr.Part.Field f -> sb.append('.').append(f.name());
            case Expr.Part.All ignored -> sb.append(".*");
            case Expr.Part.First ignored -> sb.append(".$");
            case Expr.Part.Last ignored -> sb.append(".?");
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
                sb.append('.').append(m.name()).append('(');
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
                expr(sb, g.lookup());
            }
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
            case POW -> "^";
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
            case NOT_INSIDE -> "NOT INSIDE";
            case ALL_INSIDE -> "ALLINSIDE";
            case ANY_INSIDE -> "ANYINSIDE";
            case NONE_INSIDE -> "NONEINSIDE";
            case OUTSIDE -> "OUTSIDE";
            case INTERSECTS -> "INTERSECTS";
            case MATCH -> "@@";
        };
    }

    private static void literal(StringBuilder sb, com.axonbase.value.AxonValue v) {
        switch (v.type()) {
            case STRING -> sb.append('"').append(escape(v.asString())).append('"');
            case NONE -> sb.append("NONE");
            case NULL -> sb.append("null");
            case BOOL -> sb.append(v.asBool());
            case NUMBER -> sb.append(numberLiteral(v.asDecimal()));
            case TABLE -> sb.append(v.asTable());
            default -> sb.append(v);
        }
    }

    private static String numberLiteral(java.math.BigDecimal dec) {
        String s = dec.stripTrailingZeros().toPlainString();
        if (s.endsWith(".0")) {
            s = s.substring(0, s.length() - 2);
        }
        return s;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String quoteIfNeeded(String key) {
        return key;
    }
}
