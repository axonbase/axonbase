package com.axonbase.parser;

import com.axonbase.common.AxonError;
import com.axonbase.common.Messages;
import com.axonbase.parser.ast.Expr;
import com.axonbase.parser.ast.Expr.BinaryOp;
import com.axonbase.parser.ast.Expr.Direction;
import com.axonbase.parser.ast.Expr.Part;
import com.axonbase.parser.ast.Expr.UnaryOp;
import com.axonbase.parser.ast.Query;
import com.axonbase.parser.ast.Statement;
import com.axonbase.parser.ast.Statement.Assignment;
import com.axonbase.parser.ast.Statement.Branch;
import com.axonbase.parser.ast.Statement.Data;
import com.axonbase.parser.ast.Statement.Join;
import com.axonbase.parser.ast.Statement.OrderTerm;
import com.axonbase.parser.ast.Statement.ReturnKind;
import com.axonbase.parser.ast.Statement.ReturnSpec;
import com.axonbase.parser.ast.Statement.UpdateMode;
import com.axonbase.parser.token.Lexer;
import com.axonbase.parser.token.Token;
import com.axonbase.parser.token.TokenType;
import com.axonbase.value.AxonValue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.axonbase.parser.token.TokenType.ARROW;
import static com.axonbase.parser.token.TokenType.B_ARROW;
import static com.axonbase.parser.token.TokenType.BANG;
import static com.axonbase.parser.token.TokenType.BYTES;
import static com.axonbase.parser.token.TokenType.COLON;
import static com.axonbase.parser.token.TokenType.COLON_COLON;
import static com.axonbase.parser.token.TokenType.COMMA;
import static com.axonbase.parser.token.TokenType.DATETIME;
import static com.axonbase.parser.token.TokenType.DECIMAL;
import static com.axonbase.parser.token.TokenType.DOT;
import static com.axonbase.parser.token.TokenType.DOT_DOT;
import static com.axonbase.parser.token.TokenType.DOT_DOT_EQ;
import static com.axonbase.parser.token.TokenType.DURATION;
import static com.axonbase.parser.token.TokenType.EOF;
import static com.axonbase.parser.token.TokenType.EQ;
import static com.axonbase.parser.token.TokenType.EQ_EQ;
import static com.axonbase.parser.token.TokenType.FLOAT;
import static com.axonbase.parser.token.TokenType.GE;
import static com.axonbase.parser.token.TokenType.GT;
import static com.axonbase.parser.token.TokenType.GT_DOT_DOT;
import static com.axonbase.parser.token.TokenType.GT_DOT_DOT_EQ;
import static com.axonbase.parser.token.TokenType.IDENT;
import static com.axonbase.parser.token.TokenType.INT;
import static com.axonbase.parser.token.TokenType.KEYWORD;
import static com.axonbase.parser.token.TokenType.LARROW;
import static com.axonbase.parser.token.TokenType.LBRACE;
import static com.axonbase.parser.token.TokenType.LBRACKET;
import static com.axonbase.parser.token.TokenType.LE;
import static com.axonbase.parser.token.TokenType.LPAREN;
import static com.axonbase.parser.token.TokenType.MINUS;
import static com.axonbase.parser.token.TokenType.NE;
import static com.axonbase.parser.token.TokenType.PARAM;
import static com.axonbase.parser.token.TokenType.PERCENT;
import static com.axonbase.parser.token.TokenType.PLUS;
import static com.axonbase.parser.token.TokenType.QMARK_COLON;
import static com.axonbase.parser.token.TokenType.QUESTION_QUESTION;
import static com.axonbase.parser.token.TokenType.RBRACE;
import static com.axonbase.parser.token.TokenType.RBRACKET;
import static com.axonbase.parser.token.TokenType.RPAREN;
import static com.axonbase.parser.token.TokenType.SEMI;
import static com.axonbase.parser.token.TokenType.SLASH;
import static com.axonbase.parser.token.TokenType.STAR;
import static com.axonbase.parser.token.TokenType.STAR_EQ;
import static com.axonbase.parser.token.TokenType.STAR_STAR;
import static com.axonbase.parser.token.TokenType.STRING;

/** Parser recursive descent da AxonQL, com precedência em estilo Pratt. */
public final class Parser {

    private final List<Token> tokens;
    private int pos;

    public Parser(List<Token> tokens) {
        this.tokens = tokens;
    }

    public static Query parse(String sql) {
        return new Parser(Lexer.tokenize(sql)).parseQuery();
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    private Token peek() {
        return tokens.get(pos);
    }

    private Token peek(int ahead) {
        int i = pos + ahead;
        return i < tokens.size() ? tokens.get(i) : tokens.get(tokens.size() - 1);
    }

    private Token take() {
        return tokens.get(pos++);
    }

    private boolean at(TokenType t) {
        return peek().is(t);
    }

    private boolean atKeyword(String kw) {
        return peek().isKeyword(kw);
    }

    private boolean match(TokenType t) {
        if (at(t)) {
            pos++;
            return true;
        }
        return false;
    }

    private boolean matchKeyword(String kw) {
        if (atKeyword(kw)) {
            pos++;
            return true;
        }
        return false;
    }

    /**
     * Consome um parâmetro numérico contextual do HNSW ({@code M 12}, {@code EFC 64}).
     * Os nomes {@code m}, {@code efc} e {@code efs} não são palavras-chave globais para
     * não capturar identificadores de consulta, então casam por texto em IDENT/KEYWORD.
     */
    private Integer matchParamInt(String name) {
        Token tok = peek();
        if (!(tok.type() == TokenType.IDENT || tok.type() == TokenType.KEYWORD)
            || !tok.text().equalsIgnoreCase(name)) {
            return null;
        }
        pos++;
        return ((Number) expect(TokenType.INT).literal()).intValue();
    }

    private Token expect(TokenType t) {
        Token tok = peek();
        if (!tok.is(t)) {
            throw error(Messages.get("parser_expected_token", t, tok.start(), tok.text()));
        }
        return take();
    }

    private Token expectKeyword(String kw) {
        if (!atKeyword(kw)) {
            throw error(Messages.get("parser_expected_keyword", kw, peek().start()));
        }
        return take();
    }

    private AxonError error(String msg) {
        return new AxonError(-32700, msg);
    }

    // ------------------------------------------------------------------
    // consulta e sentenças
    // ------------------------------------------------------------------

    private Query parseQuery() {
        List<Statement> stmts = new ArrayList<>();
        while (!at(EOF)) {
            while (match(SEMI)) {
                // ignora separadores extras
            }
            if (at(EOF)) {
                break;
            }
            stmts.add(parseStatement());
        }
        return new Query(stmts);
    }

    private Statement parseStatement() {
        Token t = peek();
        return switch (t.type()) {
            case KEYWORD -> statementKeyword(t);
            default -> throw error(Messages.get("parser_unexpected_statement", t.start(), t.text()));
        };
    }

    private Statement statementKeyword(Token t) {
        if (t.isKeyword("use")) {
            return parseUse();
        }
        if (t.isKeyword("set") && peek(1) != null && peek(1).isKeyword("reason")) {
            return parseSetReasonAudit();
        }
        if (t.isKeyword("set") && peek(1) != null && peek(1).isKeyword("audit")) {
            return parseSetAuditCase();
        }
        if (t.isKeyword("let") || t.isKeyword("set")) {
            return parseLet();
        }
        if (t.isKeyword("create")) {
            return parseCreate();
        }
        if (t.isKeyword("insert")) {
            return parseInsert();
        }
        if (t.isKeyword("update")) {
            return parseUpdate(UpdateMode.UPDATE);
        }
        if (t.isKeyword("upsert")) {
            return parseUpdate(UpdateMode.UPSERT);
        }
        if (t.isKeyword("delete")) {
            return parseDelete();
        }
        if (t.isKeyword("select")) {
            return parseSelect();
        }
        if (t.isKeyword("explain")) {
            return parseExplain();
        }
        if (t.isKeyword("live")) {
            return parseLive();
        }
        if (t.isKeyword("relate")) {
            return parseRelate();
        }
        if (t.isKeyword("define")) {
            return parseDefine();
        }
        if (t.isKeyword("drop")) {
            return parseDrop();
        }
        if (t.isKeyword("info")) {
            return parseInfo();
        }
        if (t.isKeyword("describe")) {
            return parseDescribe();
        }
        if (t.isKeyword("remove")) {
            return parseRemove();
        }
        if (t.isKeyword("alter")) {
            return parseAlter();
        }
        if (t.isKeyword("grant")) {
            return parseGrantAccess();
        }
        if (t.isKeyword("revoke")) {
            return parseRevokeAccess();
        }
        if (t.isKeyword("show")) {
            return parseShow();
        }
        if (t.isKeyword("return")) {
            return parseReturn();
        }
        if (t.isKeyword("if")) {
            return parseIfElse();
        }
        if (t.isKeyword("error")) {
            return parseErrorStmt();
        }
        if (t.isKeyword("kill")) {
            return parseKill();
        }
        if (t.isKeyword("begin")) {
            if (peek(1) != null && peek(1).isKeyword("saga")) {
                return parseBeginSaga();
            }
            pos++;
            return new Statement.Begin();
        }
        if (t.isKeyword("commit")) {
            if (peek(1) != null && peek(1).isKeyword("saga")) {
                return parseCommitSaga();
            }
            pos++;
            return new Statement.Commit();
        }
        if (t.isKeyword("cancel")) {
            if (peek(1) != null && peek(1).isKeyword("saga")) {
                return parseCancelSaga();
            }
            pos++;
            return new Statement.Cancel();
        }
        if (t.isKeyword("savepoint")) {
            return parseSavepoint();
        }
        if (t.isKeyword("release")) {
            return parseRelease();
        }
        if (t.isKeyword("rollback")) {
            return parseRollbackTo();
        }
        if (t.isKeyword("join")) {
            if (peek(1) != null && peek(1).isKeyword("saga")) {
                return parseJoinSaga();
            }
            throw error(Messages.get("parser_unexpected_statement", t.start(), t.text()));
        }
        if (t.isKeyword("leave")) {
            if (peek(1) != null && peek(1).isKeyword("saga")) {
                return parseLeaveSaga();
            }
            throw error(Messages.get("parser_unexpected_statement", t.start(), t.text()));
        }
        throw error(Messages.get("parser_unexpected_statement", t.start(), t.text()));
    }

    private Statement parseSavepoint() {
        pos++; // savepoint
        String name = expectIdent();
        return new Statement.Savepoint(name);
    }

    private Statement parseRelease() {
        pos++; // release
        // RELEASE SAVEPOINT <nome> ou RELEASE <nome>
        matchKeyword("savepoint");
        String name = expectIdent();
        return new Statement.Release(name);
    }

    private Statement parseRollbackTo() {
        pos++; // rollback
        expectText("to");
        matchKeyword("savepoint");
        String name = expectIdent();
        return new Statement.RollbackTo(name);
    }

    // ------------------------------------------------------------------
    // SAGA
    // ------------------------------------------------------------------

    private Statement parseSagaCreate() {
        // CREATE SAGA <name> WITH DATABASES 'link1', 'link2', ...
        String name = expectIdentOrString();
        expectKeyword("with");
        expectKeyword("databases");
        List<String> links = new ArrayList<>();
        links.add(expectString());
        while (match(COMMA)) {
            links.add(expectString());
        }
        return new Statement.CreateSaga(name, links);
    }

    private Statement parseDescribe() {
        pos++; // describe
        if (matchKeyword("saga")) {
            String name = expectIdentOrString();
            return new Statement.DescribeSaga(name);
        }
        String table = expectIdentOrString();
        return new Statement.DescribeTable(table);
    }

    private Statement parseShow() {
        pos++; // show
        if (matchKeyword("saga")) {
            expectText("transaction");
            String name = expectIdentOrString();
            String corrId = expectString();
            return new Statement.ShowSagaTransaction(name, corrId);
        }
        if (matchKeyword("ai")) {
            expectKeyword("audit");
            String name = expectIdent();
            return new Statement.ShowAiAudit(name);
        }
        if (matchKeyword("data")) {
            if (matchKeyword("rules")) {
                return new Statement.ShowDataRules();
            }
            throw error(Messages.get("parser_show_data_expected_rules", peek().start()));
        }
        throw error(Messages.get("parser_unexpected_show", peek().start(), peek().text()));
    }

    private Statement parseBeginSaga() {
        pos++; // begin
        pos++; // saga
        String name = expectIdentOrString();
        expectKeyword("with");
        expectKeyword("correlation");
        String corrId = expectString();
        return new Statement.BeginSaga(name, corrId);
    }

    private Statement parseCommitSaga() {
        pos++; // commit
        pos++; // saga
        String name = expectIdentOrString();
        expectKeyword("with");
        expectKeyword("correlation");
        String corrId = expectString();
        return new Statement.CommitSaga(name, corrId);
    }

    private Statement parseCancelSaga() {
        pos++; // cancel
        pos++; // saga
        String name = expectIdentOrString();
        expectKeyword("with");
        expectKeyword("correlation");
        String corrId = expectString();
        return new Statement.CancelSaga(name, corrId);
    }

    private Statement parseJoinSaga() {
        pos++; // join
        pos++; // saga
        String name = expectIdentOrString();
        expectKeyword("with");
        expectKeyword("correlation");
        String corrId = expectString();
        return new Statement.JoinSaga(name, corrId);
    }

    private Statement parseLeaveSaga() {
        pos++; // leave
        pos++; // saga
        return new Statement.LeaveSaga();
    }

    // ------------------------------------------------------------------
    // DATA RULE
    // ------------------------------------------------------------------

    private Statement parseCreateDataRule() {
        String name = expectIdent();
        expectKeyword("apply");
        Expr predicate = parseExpr();
        List<String> masks = new ArrayList<>();
        if (matchKeyword("mask")) {
            expectKeyword("fields");
            expectKeyword("with");
            masks.add(expectString());
            while (match(COMMA)) {
                masks.add(expectString());
            }
        }
        return new Statement.CreateDataRule(name, predicate, masks);
    }

    /** Consome unha palabra (IDENT ou KEYWORD) co texto dado, sin ser obrigatoriamente keyword. */
    private void expectText(String word) {
        Token t = peek();
        if (!(t.is(IDENT) || t.is(KEYWORD)) || !t.text().equalsIgnoreCase(word)) {
            throw error(Messages.get("parser_expected_word", word, t.start()));
        }
        pos++;
    }

    private Statement parseUse() {
        pos++;
        Expr ns = null;
        Expr db = null;
        if (matchKeyword("ns")) {
            ns = idOrParam();
        } else if (at(IDENT) || at(PARAM)) {
            ns = idOrParam();
        }
        if (matchKeyword("db")) {
            db = idOrParam();
        } else if (at(IDENT) || at(PARAM)) {
            db = idOrParam();
        }
        return new Statement.Use(ns, db);
    }

    private Statement parseLet() {
        pos++;
        Token name = expect(PARAM);
        expect(EQ);
        Expr value = parseExpr();
        return new Statement.Let((String) name.literal(), value);
    }

    private Statement parseCreate() {
        pos++;
        if (matchKeyword("table")) {
            return parseCreateTable();
        }
        if (matchKeyword("saga")) {
            return parseSagaCreate();
        }
        if (matchKeyword("ai")) {
            expectKeyword("audit");
            String name = expectIdent();
            expectKeyword("set");
            expectKeyword("warning");
            expectKeyword("when");
            String warningWhen = expectString();
            expectKeyword("set");
            expectKeyword("danger");
            expectKeyword("when");
            String dangerWhen = expectString();
            return new Statement.DefineAiAudit(name, warningWhen, dangerWhen);
        }
        if (matchKeyword("jks")) {
            String name = expectIdent();
            expectKeyword("path");
            String path = expectString();
            expectKeyword("password");
            String password = expectString();
            String collector = "NONE";
            List<String> oids = List.of();
            if (matchKeyword("collect")) {
                expectKeyword("user");
                expectKeyword("by");
                if (matchKeyword("icpbrasil")) {
                    collector = "ICPBRASIL";
                } else {
                    expectKeyword("oid");
                    List<String> values = new ArrayList<>();
                    values.add(expectString());
                    while (match(COMMA)) {
                        values.add(expectString());
                    }
                    collector = "OID";
                    oids = List.copyOf(values);
                }
            }
            return new Statement.CreateJks(name, path, password, collector, oids);
        }
        // Check for CREATE DDL statements with lookahead (to avoid ambiguity with CREATE <table>)
        if (peekDdlCreate("index", "on")) {
            pos++; // consume "index"
            return parseCreateIndex();
        }
        if (peekDdlCreate("event", "on")) {
            pos++; // consume "event"
            return parseCreateEvent();
        }
        if (peekDdlCreate("analyzer", null, "lowercase", "stemming", "stopwords")) {
            pos++; // consume "analyzer"
            return parseCreateAnalyzer();
        }
        if (peekDdlCreate("user", "on")) {
            pos++; // consume "user"
            return parseCreateUser();
        }
        if (atKeyword("database") && peek(1) != null && peek(1).isKeyword("link")) {
            pos++; pos++; // consume "database" + "link"
            return parseCreateDatabaseLink();
        }
        if (matchKeyword("data")) {
            if (matchKeyword("rule")) {
                return parseCreateDataRule();
            }
            throw error(Messages.get("parser_create_data_expected_rule", peek().start()));
        }
        boolean only = matchKeyword("only");
        Expr target = parseTarget();
        Data data = null;
        if (matchKeyword("content")) {
            data = new Data.Content(parseExpr());
        } else if (matchKeyword("set")) {
            data = new Data.SetClause(parseAssignments());
        }
        ReturnSpec ret = parseReturnSpec();
        return new Statement.Create(only, target, data, ret);
    }

    private Statement parseCreateTable() {
        boolean ifNotExists = false;
        if (matchKeyword("if")) {
            expectKeyword("not");
            expectKeyword("exists");
            ifNotExists = true;
        }
        String table = expectIdentOrString();
        expect(TokenType.LPAREN);

        List<Statement.ColumnDef> columns = new ArrayList<>();
        List<String> primaryKeyCols = new ArrayList<>();

        while (!at(TokenType.RPAREN) && !at(TokenType.EOF)) {
            // Check for table-level constraints
            if (matchKeyword("constraint")) {
                expectIdentOrString(); // skip constraint name
            }

            if (matchKeyword("primary")) {
                expectKeyword("key");
                expect(TokenType.LPAREN);
                primaryKeyCols.add(expectIdentOrString());
                while (match(COMMA)) {
                    primaryKeyCols.add(expectIdentOrString());
                }
                expect(TokenType.RPAREN);
            } else if (matchKeyword("check")) {
                expect(TokenType.LPAREN);
                parseExpr(); // table check
                expect(TokenType.RPAREN);
            } else if (matchKeyword("foreign")) {
                expectKeyword("key");
                expect(TokenType.LPAREN);
                expectIdentOrString();
                expect(TokenType.RPAREN);
                expectKeyword("references");
                expectIdentOrString();
                if (match(TokenType.LPAREN)) {
                    expectIdentOrString();
                    expect(TokenType.RPAREN);
                }
            } else {
                // Column definition: <name> <type> [<constraints>]
                String colName = expectIdentOrString();
                String colType = parseSqlColumnType();

                boolean isPk = false;
                boolean isNotNull = false;
                Expr defaultExpr = null;
                Expr checkExpr = null;
                String references = null;

                while (true) {
                    if (matchKeyword("primary")) {
                        expectKeyword("key");
                        isPk = true;
                    } else if (matchKeyword("not")) {
                        expectKeyword("null");
                        isNotNull = true;
                    } else if (matchKeyword("null")) {
                        // ignore nullable marker
                    } else if (matchKeyword("default")) {
                        defaultExpr = parseExpr();
                    } else if (matchKeyword("check")) {
                        expect(TokenType.LPAREN);
                        checkExpr = rewriteCheckExpr(parseExpr(), colName);
                        expect(TokenType.RPAREN);
                    } else if (matchKeyword("references")) {
                        references = expectIdentOrString();
                        if (match(TokenType.LPAREN)) {
                            expectIdentOrString();
                            expect(TokenType.RPAREN);
                        }
                    } else {
                        break;
                    }
                }

                columns.add(new Statement.ColumnDef(colName, colType, isPk, isNotNull, defaultExpr, checkExpr, references));
            }

            if (!match(COMMA)) {
                break;
            }
        }
        expect(TokenType.RPAREN);

        // Optional WITH (SCHEMA = 'FULL' | 'SCHEMALESS' | 'FLEXIBLE')
        boolean schemafull = true;
        if (matchKeyword("with")) {
            expect(TokenType.LPAREN);
            while (!at(TokenType.RPAREN) && !at(TokenType.EOF)) {
                if (matchKeyword("schema")) {
                    expect(TokenType.EQ);
                    String s = expectIdentOrString();
                    if ("schemaless".equalsIgnoreCase(s) || "flexible".equalsIgnoreCase(s)) {
                        schemafull = false;
                    } else if ("full".equalsIgnoreCase(s) || "schemafull".equalsIgnoreCase(s)) {
                        schemafull = true;
                    }
                } else {
                    take(); // skip unknown option
                }
                if (!match(COMMA)) break;
            }
            expect(TokenType.RPAREN);
        }

        // Apply table-level primary keys if present
        if (!primaryKeyCols.isEmpty()) {
            List<Statement.ColumnDef> updated = new ArrayList<>();
            for (Statement.ColumnDef c : columns) {
                if (primaryKeyCols.contains(c.name())) {
                    updated.add(new Statement.ColumnDef(c.name(), c.type(), true, c.notNull(), c.defaultExpr(), c.checkExpr(), c.references()));
                } else {
                    updated.add(c);
                }
            }
            columns = updated;
        }

        return new Statement.CreateTable(table, ifNotExists, schemafull, columns);
    }

    private String parseSqlColumnType() {
        String baseType = expectTypeWord().toLowerCase();
        // Check for (length / precision / subtype): e.g. VARCHAR(255), GEOMETRY(POINT), VECTOR(1536)
        if (match(TokenType.LPAREN)) {
            while (!at(TokenType.RPAREN) && !at(TokenType.EOF)) {
                take();
            }
            expect(TokenType.RPAREN);
        }
        return switch (baseType) {
            case "varchar", "char", "text", "clob", "string" -> "string";
            case "int", "integer", "bigint", "smallint", "tinyint" -> "int";
            case "decimal", "numeric" -> "decimal";
            case "float", "double", "real" -> "float";
            case "bool", "boolean" -> "bool";
            case "timestamp", "datetime", "date" -> "datetime";
            case "uuid" -> "uuid";
            case "bytes", "blob", "binary" -> "bytes";
            case "geometry", "point" -> "geometry";
            case "vector" -> "vector";
            default -> baseType;
        };
    }

    private static Expr rewriteCheckExpr(Expr expr, String colName) {
        if (expr instanceof Expr.Ident id && id.name().equalsIgnoreCase(colName)) {
            return new Expr.Param("value");
        }
        if (expr instanceof Expr.Binary b) {
            return new Expr.Binary(b.op(), rewriteCheckExpr(b.left(), colName), rewriteCheckExpr(b.right(), colName));
        }
        if (expr instanceof Expr.Unary u) {
            return new Expr.Unary(u.op(), rewriteCheckExpr(u.operand(), colName));
        }
        return expr;
    }

    private Statement parseInsert() {
        pos++;
        boolean ignore = matchKeyword("ignore");
        boolean relation = matchKeyword("relation");
        expectKeyword("into");
        String table = expectIdent();

        // Standard SQL: INSERT INTO table (col1, col2, ...) VALUES (val1, val2), (val3, val4)
        if (at(TokenType.LPAREN)) {
            pos++;
            List<String> cols = new ArrayList<>();
            cols.add(expectIdentOrString());
            while (match(COMMA)) {
                cols.add(expectIdentOrString());
            }
            expect(TokenType.RPAREN);

            expectKeyword("values");

            List<Expr> rows = new ArrayList<>();
            while (true) {
                expect(TokenType.LPAREN);
                List<Expr.ObjectLit.Entry> entries = new ArrayList<>();
                for (int i = 0; i < cols.size(); i++) {
                    if (i > 0) expect(COMMA);
                    Expr val = parseExpr();
                    entries.add(new Expr.ObjectLit.Entry(cols.get(i), val));
                }
                expect(TokenType.RPAREN);
                rows.add(new Expr.ObjectLit(entries));

                if (!match(COMMA)) break;
            }
            Expr data = new Expr.ArrayLit(rows);
            ReturnSpec ret = parseReturnSpec();
            return new Statement.Insert(ignore, relation, table, data, ret);
        } else if (matchKeyword("values")) {
            // INSERT INTO table VALUES (...)
            List<Expr> rows = new ArrayList<>();
            while (true) {
                expect(TokenType.LPAREN);
                List<Expr> vals = new ArrayList<>();
                vals.add(parseExpr());
                while (match(COMMA)) {
                    vals.add(parseExpr());
                }
                expect(TokenType.RPAREN);
                rows.add(new Expr.ArrayLit(vals));
                if (!match(COMMA)) break;
            }
            Expr data = new Expr.ArrayLit(rows);
            ReturnSpec ret = parseReturnSpec();
            return new Statement.Insert(ignore, relation, table, data, ret);
        }

        // AxonQL format: INSERT INTO table [{...}, {...}] or {...}
        Expr data = parseExpr();
        ReturnSpec ret = parseReturnSpec();
        return new Statement.Insert(ignore, relation, table, data, ret);
    }

    private Statement parseUpdate(UpdateMode mode) {
        pos++;
        boolean only = matchKeyword("only");
        Expr target = parseTarget();
        consumeAlias();
        Data data = null;
        if (matchKeyword("content")) {
            data = new Data.Content(parseExpr());
        } else if (matchKeyword("set")) {
            data = new Data.SetClause(parseAssignments());
        } else if (matchKeyword("merge")) {
            data = new Data.Merge(parseExpr());
        } else if (matchKeyword("patch")) {
            data = new Data.Patch(parseExpr());
        } else if (matchKeyword("replace")) {
            data = new Data.Replace(parseExpr());
        }
        Expr cond = null;
        if (matchKeyword("where")) {
            cond = parseExpr();
        }
        ReturnSpec ret = parseReturnSpec();
        return new Statement.Update(mode, only, target, data, cond, ret);
    }

    private Statement parseDelete() {
        pos++;
        Expr target = parseTarget();
        consumeAlias();
        Expr cond = null;
        if (matchKeyword("where")) {
            cond = parseExpr();
        }
        ReturnSpec ret = parseReturnSpec();
        return new Statement.Delete(target, cond, ret);
    }

    private Statement parseSelect() {
        pos++;
        return parseSelectBody();
    }

    private Statement parseSelectBody() {
        boolean valueOnly = matchKeyword("value");
        List<Expr> fields = new ArrayList<>();
        while (true) {
            if (match(STAR)) {
                fields.add(new Expr.Ident("*"));
            } else {
                Expr field = parseExpr();
                if (matchKeyword("as")) {
                    field = new Expr.Alias(field, expectIdentOrString());
                }
                fields.add(field);
            }
            if (!match(COMMA)) {
                break;
            }
        }
        boolean only = matchKeyword("only");
        Expr from;
        boolean hasFrom = matchKeyword("from");
        if (hasFrom) {
            from = parseTarget();
            consumeAlias();
        } else if (valueOnly) {
            from = null;
        } else {
            throw error(Messages.get("parser_select_expected_from"));
        }
        Statement.TimeTravel timeTravel = parseTimeTravel();
        List<Join> joins = new ArrayList<>();
        while (matchKeyword("join")) {
            Expr source = parseTarget();
            consumeAlias();
            expectKeyword("on");
            Expr on = parseExpr();
            if (!(on instanceof Expr.Binary bin) || bin.op() != Expr.BinaryOp.EQ) {
                throw error(Messages.get("parser_join_expected_condition"));
            }
            joins.add(new Join(source, bin.left(), bin.right()));
        }
        Expr cond = null;
        if (matchKeyword("where")) {
            cond = parseExpr();
        }
        List<OrderTerm> orders = new ArrayList<>();
        if (matchKeyword("order")) {
            expectKeyword("by");
            while (true) {
                Expr f = parseExpr();
                boolean desc = false;
                if (matchKeyword("desc")) {
                    desc = true;
                } else {
                    matchKeyword("asc");
                }
                orders.add(new OrderTerm(f, desc));
                if (!match(COMMA)) {
                    break;
                }
            }
        }
        List<Expr> group = new ArrayList<>();
        if (matchKeyword("group")) {
            expectKeyword("by");
            group.add(parseExpr());
            while (match(COMMA)) {
                group.add(parseExpr());
            }
        }
        // START e LIMIT aceptados en calquera orde entre order/fetch
        Expr limit = null;
        Expr start = null;
        boolean seenLimit = false;
        boolean seenStart = false;
        while (true) {
            if (matchKeyword("limit") && !seenLimit) {
                limit = parseExpr();
                seenLimit = true;
            } else if (matchKeyword("start") && !seenStart) {
                start = parseExpr();
                seenStart = true;
            } else {
                break;
            }
        }
        List<String> fetch = new ArrayList<>();
        if (matchKeyword("fetch")) {
            fetch.add(expectIdent());
            while (match(COMMA)) {
                fetch.add(expectIdent());
            }
        }
        // ONLY pode colocarse despois do FROM (SurrealQL tamén o acepta así)
        if (matchKeyword("only")) {
            only = true;
        }
        return new Statement.Select(fields, valueOnly, only, from, cond, orders, group,
            limit, start, fetch, joins, timeTravel);
    }

    private Statement.TimeTravel parseTimeTravel() {
        Statement.TimeTravelMode mode;
        if (matchKeyword("at")) {
            mode = Statement.TimeTravelMode.AT;
        } else if (matchKeyword("before")) {
            mode = Statement.TimeTravelMode.BEFORE;
        } else {
            return null;
        }

        expect(LPAREN);
        Statement.TimeTravelSelector selector;
        if (matchKeyword("timestamp")) {
            selector = Statement.TimeTravelSelector.TIMESTAMP;
        } else if (matchKeyword("commit")) {
            selector = Statement.TimeTravelSelector.COMMIT;
        } else if (matchKeyword("statement")) {
            selector = Statement.TimeTravelSelector.STATEMENT;
        } else {
            throw error(Messages.get("parser_time_travel_expected_selector", peek().start()));
        }
        expect(ARROW);
        Expr value = parseExpr();
        expect(RPAREN);
        return new Statement.TimeTravel(mode, selector, value);
    }

    /** LIVE SELECT ... [DIFF]: consome 'live' e delega no parseSelect. */
    private Statement parseLive() {
        pos++; // live
        boolean diff = false;
        Statement parsed = parseSelect();
        if (!(parsed instanceof Statement.Select sel)) {
            throw error(Messages.get("parser_live_expected_select"));
        }
        if (matchKeyword("diff")) {
            diff = true;
        }
        return new Statement.Live(sel, diff);
    }

    /**
     * EXPLAIN SELECT ... e EXPLAIN ANALYZE SELECT ...: envolve o SELECT seguinte
     * nun plano. ANALYZE ordénase executar o SELECT e contar as filas reais.
     */
    private Statement parseExplain() {
        pos++; // explain
        boolean analyze = matchKeyword("analyze");
        Statement parsed = parseSelect();
        if (!(parsed instanceof Statement.Select sel)) {
            throw error(Messages.get("parser_explain_expected_select"));
        }
        return new Statement.Explain(sel, analyze);
    }

    private Statement parseRelate() {
        pos++;
        Expr from = parseTarget();
        expect(ARROW);
        Expr kind = parseTarget();
        Expr to = null;
        if (at(ARROW)) {
            pos++;
            to = parseTarget();
        }
        Data data = null;
        if (matchKeyword("set")) {
            data = new Data.SetClause(parseAssignments());
        }
        ReturnSpec ret = parseReturnSpec();
        return new Statement.Relate(from, kind, to, data, ret);
    }

    private Statement.Permissions parsePermissions() {
        Expr sel = null;
        Expr crt = null;
        Expr upd = null;
        Expr del = null;
        matchKeyword("for");
        while (true) {
            String action = expectIdent();
            if (matchKeyword("where")) {
                Expr cond = parseExpr();
                switch (action) {
                    case "select" -> sel = cond;
                    case "create" -> crt = cond;
                    case "update" -> upd = cond;
                    case "delete" -> del = cond;
                    default -> throw error(Messages.get("parser_unknown_permission_action", action));
                }
            }
            if (matchKeyword("for")) {
                continue;
            }
            break;
        }
        return new Statement.Permissions(sel, crt, upd, del);
    }

    private String expectIdentWord() {
        Token t = peek();
        if (t.is(IDENT) || t.is(KEYWORD)) {
            return take().text();
        }
        return expect(IDENT).text();
    }

    /** Consome um alias opcional após um target de FROM/JOIN (ex.: {@code FROM users u}). */
    private void consumeAlias() {
        Token t = peek();
        if (t == null || !t.is(IDENT)) return;
        String text = t.text();
        if (!"join".equals(text) && !"where".equals(text) && !"order".equals(text)
            && !"limit".equals(text) && !"start".equals(text) && !"group".equals(text)
            && !"fetch".equals(text) && !"on".equals(text)) {
            pos++;
        }
    }

    private Statement parseDefine() {
        pos++;
        if (matchKeyword("analyzer")) {
            String name = expectIdent();
            boolean lowercase = false;
            boolean stemming = false;
            List<String> stopwords = new ArrayList<>();
            while (true) {
                if (matchKeyword("lowercase")) {
                    lowercase = true;
                } else if (matchKeyword("stemming")) {
                    stemming = true;
                } else if (matchKeyword("stopwords")) {
                    Token word = expect(TokenType.STRING);
                    stopwords.add((String) word.literal());
                    while (match(COMMA)) {
                        Token next = expect(TokenType.STRING);
                        stopwords.add((String) next.literal());
                    }
                } else {
                    break;
                }
            }
            return new Statement.DefineAnalyzer(name, lowercase, stopwords, stemming);
        }
        if (matchKeyword("user")) {
            String name = expectIdentOrString();
            AuthTarget target = parseAuthTarget();
            Expr password = null;
            String passhash = null;
            String certificate = null;
            String fingerprint = null;
            if (matchKeyword("passhash")) {
                passhash = expectString();
            } else if (matchKeyword("password")) {
                password = parseExpr();
            } else if (matchKeyword("certificate")) {
                certificate = expectIdent();
                if (matchKeyword("fingerprint")) {
                    fingerprint = expectString();
                }
            } else {
                throw error(Messages.get("parser_define_user_expected_credential"));
            }
            List<String> roles = new ArrayList<>();
            if (matchKeyword("roles")) {
                roles.add(expectIdent());
                while (match(COMMA)) {
                    roles.add(expectIdent());
                }
            }
            List<String> dataRules = new ArrayList<>();
            if (matchKeyword("apply")) {
                expectKeyword("data");
                expectKeyword("rule");
                dataRules.add(expectIdent());
                while (match(COMMA)) {
                    dataRules.add(expectIdent());
                }
            }
            String auditName = null;
            if (matchKeyword("audited")) {
                expectKeyword("by");
                auditName = expectIdent();
            }
            return new Statement.DefineUser(name, target.scope(), target.namespace(),
                target.database(), password, passhash, certificate, fingerprint, roles, dataRules, auditName);
        }
        if (matchKeyword("access")) {
            String name = expectIdent();
            AuthTarget target = parseAuthTarget();
            return new Statement.DefineAccess(name, target.scope(), target.namespace(),
                target.database());
        }
        if (matchKeyword("table")) {
            String name = expectIdent();
            boolean schemafull = false;
            boolean drop = matchKeyword("drop");
            if (matchKeyword("schemafull")) {
                schemafull = true;
            } else {
                matchKeyword("schemaless");
            }
            Statement.Permissions perms = Statement.Permissions.none();
            if (matchKeyword("permissions")) {
                perms = parsePermissions();
            }
            return new Statement.DefineTable(name, schemafull, drop, perms);
        }
        if (matchKeyword("field")) {
            String name = parseFieldPath();
            expectKeyword("on");
            expectKeyword("table");
            String table = expectIdent();
            String type = null;
            Expr assertE = null;
            boolean readonly = false;
            Expr valueE = null;
            Expr defaultE = null;
            if (matchKeyword("type")) {
                type = parseTypeText();
            }
            if (matchKeyword("assert")) {
                assertE = parseExpr();
            }
            if (matchKeyword("readonly")) {
                readonly = true;
            }
            if (matchKeyword("value")) {
                valueE = parseExpr();
            }
            if (matchKeyword("default")) {
                defaultE = parseExpr();
            }
            String references = null;
            if (matchKeyword("references")) {
                references = expectIdent();
            }
            return new Statement.DefineField(name, table, type, assertE, readonly, valueE, defaultE, references);
        }
        if (matchKeyword("index")) {
            String name = expectIdent();
            expectKeyword("on");
            expectKeyword("table");
            String table = expectIdent();
            expectKeyword("columns");
            List<String> columns = new ArrayList<>();
            columns.add(parseFieldPath());
            while (match(COMMA)) {
                columns.add(parseFieldPath());
            }
            boolean unique = matchKeyword("unique");
            boolean count = !unique && matchKeyword("count");
            String searchAnalyzer = null;
            boolean geo = false;
            boolean columnar = false;
            Integer vectorDimension = null;
            String vectorDistance = null;
            Integer hnswM = null;
            Integer hnswEfc = null;
            Integer hnswEfs = null;
            if (!unique && !count && matchKeyword("search")) {
                expectKeyword("analyzer");
                searchAnalyzer = expectIdent();
            } else if (!unique && !count && matchKeyword("geo")) {
                geo = true;
            } else if (!unique && !count && matchKeyword("columnar")) {
                columnar = true;
            } else if (!unique && !count && matchKeyword("hnsw")) {
                expectKeyword("dimension");
                vectorDimension = ((Number) expect(TokenType.INT).literal()).intValue();
                if (matchKeyword("dist")) {
                    vectorDistance = expectIdent();
                } else {
                    vectorDistance = "euclidean";
                }
                while (true) {
                    if (hnswM == null && (hnswM = matchParamInt("m")) != null) {
                        continue;
                    }
                    if (hnswEfc == null && (hnswEfc = matchParamInt("efc")) != null) {
                        continue;
                    }
                    if (hnswEfs == null && (hnswEfs = matchParamInt("efs")) != null) {
                        continue;
                    }
                    break;
                }
            }
            return new Statement.DefineIndex(name, table, columns, unique, count, searchAnalyzer,
                geo, columnar, vectorDimension, vectorDistance, hnswM, hnswEfc, hnswEfs);
        }
        if (matchKeyword("event")) {
            String name = expectIdent();
            expectKeyword("on");
            expectKeyword("table");
            String table = expectIdent();
            expectKeyword("when");
            Expr when = parseExpr();
            expectKeyword("then");
            List<Statement> then = parseParenStatements();
            return new Statement.DefineEvent(name, table, when, then);
        }
        if (matchKeyword("ai")) {
            expectKeyword("audit");
            String name = expectIdent();
            return new Statement.DropAiAudit(name);
        }
        if (matchKeyword("database")) {
            if (matchKeyword("link")) {
                String name = expectIdentOrString();
                expectKeyword("connect");
                expectKeyword("by");
                String url = expectString();
                String linkNs = "";
                String linkDb = "";
                String linkUser = "";
                String linkPassword = "";
                if (matchKeyword("with")) {
                    linkNs = parseKvAfterWith("ns");
                    linkDb = parseKvAfterWith("db");
                    linkUser = parseKvAfterWith("user");
                    linkPassword = parseKvAfterWith("password");
                }
                return new Statement.DefineDatabaseLink(name, url, linkNs, linkDb, linkUser, linkPassword);
            }
        }
        throw error(Messages.get("parser_unexpected_define", peek().start(), peek().text()));
    }

    // ------------------------------------------------------------------
    // New SQL-standard CREATE variants
    // ------------------------------------------------------------------

    private Statement parseCreateIndex() {
        String name = expectIdent();
        expectKeyword("on");
        expectKeyword("table");
        String table = expectIdent();
        expectKeyword("columns");
        List<String> columns = new ArrayList<>();
        columns.add(parseFieldPath());
        while (match(COMMA)) {
            columns.add(parseFieldPath());
        }
        boolean unique = matchKeyword("unique");
        boolean count = !unique && matchKeyword("count");
        String searchAnalyzer = null;
        boolean geo = false;
        boolean columnar = false;
        Integer vectorDimension = null;
        String vectorDistance = null;
        Integer hnswM = null;
        Integer hnswEfc = null;
        Integer hnswEfs = null;
        if (!unique && !count && matchKeyword("search")) {
            expectKeyword("analyzer");
            searchAnalyzer = expectIdent();
        } else if (!unique && !count && matchKeyword("geo")) {
            geo = true;
        } else if (!unique && !count && matchKeyword("columnar")) {
            columnar = true;
        } else if (!unique && !count && matchKeyword("hnsw")) {
            expectKeyword("dimension");
            vectorDimension = ((Number) expect(TokenType.INT).literal()).intValue();
            if (matchKeyword("dist")) {
                vectorDistance = expectIdent();
            } else {
                vectorDistance = "euclidean";
            }
            while (true) {
                if (hnswM == null && (hnswM = matchParamInt("m")) != null) continue;
                if (hnswEfc == null && (hnswEfc = matchParamInt("efc")) != null) continue;
                if (hnswEfs == null && (hnswEfs = matchParamInt("efs")) != null) continue;
                break;
            }
        }
        return new Statement.CreateIndex(name, table, columns, unique, count, searchAnalyzer,
            geo, columnar, vectorDimension, vectorDistance, hnswM, hnswEfc, hnswEfs);
    }

    private Statement parseCreateEvent() {
        String name = expectIdent();
        expectKeyword("on");
        expectKeyword("table");
        String table = expectIdent();
        expectKeyword("when");
        Expr when = parseExpr();
        expectKeyword("then");
        List<Statement> then = parseParenStatements();
        return new Statement.CreateEvent(name, table, when, then);
    }

    private Statement parseCreateAnalyzer() {
        String name = expectIdent();
        boolean lowercase = false;
        boolean stemming = false;
        List<String> stopwords = new ArrayList<>();
        while (true) {
            if (matchKeyword("lowercase")) {
                lowercase = true;
            } else if (matchKeyword("stemming")) {
                stemming = true;
            } else if (matchKeyword("stopwords")) {
                Token word = expect(TokenType.STRING);
                stopwords.add((String) word.literal());
                while (match(COMMA)) {
                    Token next = expect(TokenType.STRING);
                    stopwords.add((String) next.literal());
                }
            } else {
                break;
            }
        }
        return new Statement.CreateAnalyzer(name, lowercase, stopwords, stemming);
    }

    private Statement parseCreateUser() {
        String name = expectIdentOrString();
        AuthTarget target = parseAuthTarget();
        Expr password = null;
        String passhash = null;
        String certificate = null;
        String fingerprint = null;
        if (matchKeyword("passhash")) {
            passhash = expectString();
        } else if (matchKeyword("password")) {
            password = parseExpr();
        } else if (matchKeyword("certificate")) {
            certificate = expectIdent();
            if (matchKeyword("fingerprint")) {
                fingerprint = expectString();
            }
        } else {
            throw error(Messages.get("parser_define_user_expected_credential"));
        }
        List<String> roles = new ArrayList<>();
        if (matchKeyword("roles")) {
            roles.add(expectIdent());
            while (match(COMMA)) {
                roles.add(expectIdent());
            }
        }
        List<String> dataRules = new ArrayList<>();
        if (matchKeyword("apply")) {
            expectKeyword("data");
            expectKeyword("rule");
            dataRules.add(expectIdent());
            while (match(COMMA)) {
                dataRules.add(expectIdent());
            }
        }
        String auditName = null;
        if (matchKeyword("audited")) {
            expectKeyword("by");
            auditName = expectIdent();
        }
        return new Statement.CreateUser(name, target.scope(), target.namespace(),
            target.database(), password, passhash, certificate, fingerprint, roles, dataRules, auditName);
    }

    private Statement parseCreateDatabaseLink() {
        String name = expectIdentOrString();
        expectKeyword("connect");
        expectKeyword("by");
        String url = expectString();
        String linkNs = "";
        String linkDb = "";
        String linkUser = "";
        String linkPassword = "";
        if (matchKeyword("with")) {
            linkNs = parseKvAfterWith("ns");
            linkDb = parseKvAfterWith("db");
            linkUser = parseKvAfterWith("user");
            linkPassword = parseKvAfterWith("password");
        }
        return new Statement.CreateDatabaseLink(name, url, linkNs, linkDb, linkUser, linkPassword);
    }

    // ------------------------------------------------------------------
    // ALTER TABLE
    // ------------------------------------------------------------------

    private Statement parseAlter() {
        pos++;
        if (matchKeyword("table")) {
            String name = expectIdentOrString();
            List<Statement.AlterOp> ops = new ArrayList<>();
            while (true) {
                if (matchKeyword("add")) {
                    matchKeyword("column");
                    String colName = expectIdentOrString();
                    String colType = parseSqlColumnType();
                    boolean isPk = false;
                    boolean isNotNull = false;
                    Expr defaultExpr = null;
                    Expr checkExpr = null;
                    String references = null;
                    while (true) {
                        if (matchKeyword("primary")) { expectKeyword("key"); isPk = true; }
                        else if (matchKeyword("not")) { expectKeyword("null"); isNotNull = true; }
                        else if (matchKeyword("null")) { /* ignore */ }
                        else if (matchKeyword("default")) { defaultExpr = parseExpr(); }
                        else if (matchKeyword("check")) { expect(TokenType.LPAREN); checkExpr = rewriteCheckExpr(parseExpr(), colName); expect(TokenType.RPAREN); }
                        else if (matchKeyword("references")) {
                            references = expectIdentOrString();
                            if (match(TokenType.LPAREN)) { expectIdentOrString(); expect(TokenType.RPAREN); }
                        } else { break; }
                    }
                    ops.add(new Statement.AddColumn(colName, colType, isPk, isNotNull, defaultExpr, checkExpr, references));
                } else if (matchKeyword("drop")) {
                    matchKeyword("column");
                    String colName = expectIdentOrString();
                    ops.add(new Statement.DropColumn(colName));
                } else if (matchKeyword("modify")) {
                    matchKeyword("column");
                    String colName = expectIdentOrString();
                    String colType = parseSqlColumnType();
                    boolean isNotNull = false;
                    Expr defaultExpr = null;
                    Expr checkExpr = null;
                    while (true) {
                        if (matchKeyword("not")) { expectKeyword("null"); isNotNull = true; }
                        else if (matchKeyword("null")) { /* ignore */ }
                        else if (matchKeyword("default")) { defaultExpr = parseExpr(); }
                        else if (matchKeyword("check")) { expect(TokenType.LPAREN); checkExpr = rewriteCheckExpr(parseExpr(), colName); expect(TokenType.RPAREN); }
                        else { break; }
                    }
                    ops.add(new Statement.ModifyColumn(colName, colType, isNotNull, defaultExpr, checkExpr));
                } else {
                    break;
                }
                if (!match(COMMA)) break;
            }
            if (ops.isEmpty()) {
                throw error(Messages.get("parser_alter_table_no_ops", peek().start()));
            }
            return new Statement.AlterTable(name, ops);
        }
        if (matchKeyword("saga")) {
            String name = expectIdentOrString();
            expectKeyword("with");
            expectKeyword("databases");
            List<String> links = new ArrayList<>();
            links.add(expectString());
            while (match(COMMA)) {
                links.add(expectString());
            }
            return new Statement.CreateSaga(name, links);
        }
        if (matchKeyword("index")) {
            // Future: ALTER INDEX <name> ... for now return the DEFINE INDEX record
            String name = expectIdent();
            expectKeyword("on");
            expectKeyword("table");
            String table = expectIdent();
            expectKeyword("columns");
            List<String> columns = new ArrayList<>();
            columns.add(parseFieldPath());
            while (match(COMMA)) columns.add(parseFieldPath());
            boolean unique = matchKeyword("unique");
            boolean count = !unique && matchKeyword("count");
            String searchAnalyzer = null;
            boolean geo = false;
            boolean columnar = false;
            Integer vectorDimension = null;
            String vectorDistance = null;
            Integer hnswM = null;
            Integer hnswEfc = null;
            Integer hnswEfs = null;
            if (!unique && !count && matchKeyword("search")) { expectKeyword("analyzer"); searchAnalyzer = expectIdent(); }
            else if (!unique && !count && matchKeyword("geo")) { geo = true; }
            else if (!unique && !count && matchKeyword("columnar")) { columnar = true; }
            else if (!unique && !count && matchKeyword("hnsw")) {
                expectKeyword("dimension");
                vectorDimension = ((Number) expect(TokenType.INT).literal()).intValue();
                if (matchKeyword("dist")) { vectorDistance = expectIdent(); } else { vectorDistance = "euclidean"; }
                while (true) {
                    if (hnswM == null && (hnswM = matchParamInt("m")) != null) continue;
                    if (hnswEfc == null && (hnswEfc = matchParamInt("efc")) != null) continue;
                    if (hnswEfs == null && (hnswEfs = matchParamInt("efs")) != null) continue;
                    break;
                }
            }
            return new Statement.CreateIndex(name, table, columns, unique, count, searchAnalyzer, geo, columnar, vectorDimension, vectorDistance, hnswM, hnswEfc, hnswEfs);
        }
        if (matchKeyword("event")) {
            String name = expectIdent();
            expectKeyword("on");
            expectKeyword("table");
            String table = expectIdent();
            expectKeyword("when");
            Expr when = parseExpr();
            expectKeyword("then");
            List<Statement> then = parseParenStatements();
            return new Statement.CreateEvent(name, table, when, then);
        }
        if (matchKeyword("analyzer")) {
            String name = expectIdent();
            boolean lowercase = false;
            boolean stemming = false;
            List<String> stopwords = new ArrayList<>();
            while (true) {
                if (matchKeyword("lowercase")) { lowercase = true; }
                else if (matchKeyword("stemming")) { stemming = true; }
                else if (matchKeyword("stopwords")) {
                    stopwords.add((String) expect(TokenType.STRING).literal());
                    while (match(COMMA)) { stopwords.add((String) expect(TokenType.STRING).literal()); }
                } else { break; }
            }
            return new Statement.CreateAnalyzer(name, lowercase, stopwords, stemming);
        }
        if (matchKeyword("user")) {
            return parseCreateUser();
        }
        if (matchKeyword("database")) {
            if (matchKeyword("link")) {
                String name = expectIdentOrString();
                expectKeyword("connect");
                expectKeyword("by");
                String url = expectString();
                String linkNs = "";
                String linkDb = "";
                String linkUser = "";
                String linkPassword = "";
                if (matchKeyword("with")) {
                    linkNs = parseKvAfterWith("ns");
                    linkDb = parseKvAfterWith("db");
                    linkUser = parseKvAfterWith("user");
                    linkPassword = parseKvAfterWith("password");
                }
                return new Statement.AlterDatabaseLink(name, url, linkNs, linkDb, linkUser, linkPassword);
            }
        }
        throw error(Messages.get("parser_unexpected_alter", peek().start(), peek().text()));
    }

    // ------------------------------------------------------------------
    // GRANT / REVOKE ACCESS
    // ------------------------------------------------------------------

    private Statement parseGrantAccess() {
        pos++;
        expectKeyword("access");
        String name = expectIdent();
        AuthTarget target = parseAuthTarget();
        return new Statement.GrantAccess(name, target.scope(), target.namespace(), target.database());
    }

    private Statement parseRevokeAccess() {
        pos++;
        expectKeyword("access");
        String name = expectIdent();
        return new Statement.RevokeAccess(name);
    }

    private Statement parseDrop() {
        pos++;
        if (matchKeyword("ai")) {
            expectKeyword("audit");
            String name = expectIdent();
            return new Statement.DropAiAudit(name);
        }
        if (matchKeyword("data")) {
            if (matchKeyword("rule")) {
                String name = expectIdent();
                return new Statement.DropDataRule(name);
            }
            throw error(Messages.get("parser_drop_data_expected_rule", peek().start()));
        }
        if (matchKeyword("database")) {
            if (matchKeyword("link")) {
                String name = expectIdentOrString();
                return new Statement.DropDatabaseLink(name);
            }
        }
        if (matchKeyword("table")) {
            String name = expectIdentOrString();
            return new Statement.DropTable(name);
        }
        if (matchKeyword("index")) {
            String name = expectIdent();
            return new Statement.DropIndex(name);
        }
        if (matchKeyword("event")) {
            String name = expectIdent();
            return new Statement.DropEvent(name);
        }
        if (matchKeyword("analyzer")) {
            String name = expectIdent();
            return new Statement.DropAnalyzer(name);
        }
        if (matchKeyword("user")) {
            String name = expectIdentOrString();
            return new Statement.DropUser(name);
        }
        if (matchKeyword("saga")) {
            String name = expectIdentOrString();
            return new Statement.DropSaga(name);
        }
        throw error(Messages.get("parser_unexpected_drop", peek().start(), peek().text()));
    }

    private Statement parseRemove() {
        pos++;
        if (matchKeyword("table")) {
            String name = expectIdent();
            return new Statement.RemoveTable(name);
        }
        if (matchKeyword("ai")) {
            expectKeyword("audit");
            String name = expectIdent();
            return new Statement.DropAiAudit(name);
        }
        throw error(Messages.get("parser_unexpected_remove", peek().start(), peek().text()));
    }

    private AuthTarget parseAuthTarget() {
        expectKeyword("on");
        if (matchKeyword("root")) {
            return new AuthTarget(Statement.AuthScope.ROOT, null, null);
        }
        if (matchKeyword("namespace")) {
            String ns = at(IDENT) ? expectIdent() : null;
            return new AuthTarget(Statement.AuthScope.NAMESPACE, ns, null);
        }
        if (matchKeyword("database")) {
            String id1 = at(IDENT) ? expectIdent() : null;
            String id2 = id1 != null && at(IDENT) ? expectIdent() : null;
            if (id2 != null) {
                return new AuthTarget(Statement.AuthScope.DATABASE, id1, id2);
            }
            return new AuthTarget(Statement.AuthScope.DATABASE, null, id1);
        }
        throw error(Messages.get("parser_invalid_auth_target"));
    }

    private record AuthTarget(Statement.AuthScope scope, String namespace, String database) {
    }

    private Statement parseInfo() {
        pos++;
        matchKeyword("for");
        if (matchKeyword("root")) {
            return new Statement.Info("root", null);
        }
        if (matchKeyword("namespace")) {
            return new Statement.Info("namespace", null);
        }
        if (matchKeyword("ai")) {
            expectKeyword("audit");
            String name = expectIdent();
            return new Statement.DropAiAudit(name);
        }
        if (matchKeyword("database")) {
            return new Statement.Info("database", null);
        }
        if (matchKeyword("table")) {
            return new Statement.Info("table", expectIdent());
        }
        throw error(Messages.get("parser_invalid_info", peek().start()));
    }

    private Statement parseReturn() {
        pos++;
        Expr value = parseExpr();
        return new Statement.Return(value);
    }

    private Statement parseIfElse() {
        pos++;
        List<Branch> branches = new ArrayList<>();
        Expr cond = parseExpr();
        expectKeyword("then");
        Expr thenExpr = parseExprOrBlock();
        branches.add(new Branch(cond, thenExpr));
        Expr elseExpr = null;
        while (matchKeyword("else")) {
            if (matchKeyword("if")) {
                Expr c = parseExpr();
                expectKeyword("then");
                Expr t = parseExprOrBlock();
                branches.add(new Branch(c, t));
            } else {
                elseExpr = parseExprOrBlock();
            }
        }
        expectKeyword("end");
        return new Statement.IfElse(branches, elseExpr);
    }

    private Expr parseExprOrBlock() {
        if (at(TokenType.LBRACE)) {
            return parseObjectOrSet();
        }
        return parseExpr();
    }

    private Statement parseErrorStmt() {
        pos++;
        return new Statement.ErrorStmt(parseExpr());
    }

    private Statement parseSetReasonAudit() {
        pos++; // set
        pos++; // reason
        expectKeyword("audit");
        expectKeyword("case");
        String hash = expectString();
        String reason = expectString();
        return new Statement.SetReasonAudit(hash, reason);
    }

    private Statement parseSetAuditCase() {
        pos++; // set
        pos++; // audit
        expectKeyword("case");
        String hash = expectString();
        String status = expectIdent().toUpperCase();
        if (!"AUTHORIZED".equals(status) && !"DENIED".equals(status)) {
            throw error(Messages.get("parser_invalid_audit_case_status", status));
        }
        expectKeyword("reason");
        String reason = expectString();
        return new Statement.SetAuditCase(hash, status, reason);
    }

    private Statement parseKill() {
        pos++;
        return new Statement.Kill(parseExpr());
    }

    // ------------------------------------------------------------------
    // auxiliares de sentença
    // ------------------------------------------------------------------

    private Expr idOrParam() {
        if (at(PARAM)) {
            Token t = take();
            return new Expr.Param((String) t.literal());
        }
        return new Expr.Ident(expectIdent());
    }

    private String expectIdent() {
        Token t = peek();
        if (t.is(IDENT)) {
            return take().text();
        }
        if (t.is(KEYWORD)) {
            return take().text();
        }
        return expect(IDENT).text();
    }

    /** Nome de apelido: identificador nu ou texto entre aspas. */
    private String expectIdentOrString() {
        Token t = peek();
        if (t.is(TokenType.STRING)) {
            Object literal = take().literal();
            if (literal instanceof AxonValue av && av.isString()) {
                return av.asString();
            }
            return String.valueOf(literal);
        }
        return expectIdent();
    }

    private String expectString() {
        return (String) expect(TokenType.STRING).literal();
    }

    /** Lê {@code chave = "valor"} ou {@code chave = valor} ou {@code chave "valor"} após um {@code WITH}. */
    private String parseKvAfterWith(String expectedKey) {
        Token t = peek();
        if (t == null) return "";
        String text = t.text();
        if (!expectedKey.equals(text)) return "";
        pos++;
        match(TokenType.EQ);
        if (at(TokenType.STRING)) {
            return expectString();
        }
        return expectIdent();
    }

    private String parseFieldPath() {
        StringBuilder sb = new StringBuilder(expectIdent());
        while (match(DOT)) {
            sb.append('.').append(expectIdent());
        }
        return sb.toString();
    }

    /** Alvo (tabela ou record id). */
    private Expr parseTarget() {
        if (at(PARAM)) {
            Token t = take();
            return new Expr.Param((String) t.literal());
        }
        // FROM (SELECT ...) e FROM [ ... ] tratam o resultado como origem
        if (at(TokenType.LPAREN) || at(TokenType.LBRACKET)) {
            return parseExpr();
        }
        String name = expectIdentOrString();
        // "db"."table" → Qualified
        if (match(DOT)) {
            String table = expectIdentOrString();
            if (at(COLON)) {
                pos++;
                Expr key = parseRecordKey();
                return new Expr.RecordId(new Expr.Qualified(name, table), key);
            }
            return new Expr.Qualified(name, table);
        }
        if (at(COLON)) {
            pos++;
            Expr key = parseRecordKey();
            return new Expr.RecordId(new Expr.Ident(name), key);
        }
        return new Expr.Ident(name);
    }

    private Expr parseRecordKey() {
        Token t = peek();
        return switch (t.type()) {
            case INT, FLOAT, DECIMAL, STRING, DATETIME, TokenType.UUID -> literalValue(take());
            case IDENT -> {
                String name = take().text();
                yield new Expr.Literal(AxonValue.str(name));
            }
            case PARAM -> new Expr.Param((String) t.literal());
            case LBRACKET -> parseArrayLiteral();
            case LBRACE -> parseObjectOrSet();
            default -> throw error(Messages.get("parser_invalid_record_key", t.start(), t.text()));
        };
    }

    private String parseTypeText() {
        StringBuilder sb = new StringBuilder(expectTypeWord());
        if (match(TokenType.LT)) {
            sb.append('<');
            sb.append(expectTypeWord());
            while (match(COMMA)) {
                sb.append(',').append(expectTypeWord());
            }
            expect(TokenType.GT);
            sb.append('>');
        }
        return sb.toString();
    }

    private String expectTypeWord() {
        Token t = peek();
        if (t.is(IDENT)) {
            return take().text();
        }
        if (t.is(KEYWORD)) {
            return take().text();
        }
        return expect(IDENT).text();
    }

    private List<Assignment> parseAssignments() {
        List<Assignment> list = new ArrayList<>();
        while (true) {
            Expr path = parseAssignmentPath();
            expect(EQ);
            Expr value = parseExpr();
            list.add(new Assignment(path, value));
            if (!match(COMMA)) {
                break;
            }
        }
        return list;
    }

    private Expr parseAssignmentPath() {
        String name = expectIdent();
        List<Part> parts = new ArrayList<>();
        while (match(DOT)) {
            parts.add(new Part.Field(expectIdent()));
        }
        while (match(TokenType.LBRACKET)) {
            Expr idx = parseExpr();
            expect(TokenType.RBRACKET);
            parts.add(new Part.Index(idx));
        }
        if (parts.isEmpty()) {
            return new Expr.Ident(name);
        }
        return new Expr.Idiom(new Expr.Ident(name), parts);
    }

    private ReturnSpec parseReturnSpec() {
        if (!matchKeyword("return")) {
            return ReturnSpec.AFTER;
        }
        if (matchKeyword("before")) {
            return new ReturnSpec(ReturnKind.BEFORE, null);
        }
        if (matchKeyword("after")) {
            return ReturnSpec.AFTER;
        }
        if (matchKeyword("none")) {
            return ReturnSpec.NONE;
        }
        if (matchKeyword("diff")) {
            return new ReturnSpec(ReturnKind.DIFF, null);
        }
        return new ReturnSpec(ReturnKind.EXPR, parseExpr());
    }

    private List<Statement> parseParenStatements() {
        expect(LPAREN);
        List<Statement> stmts = new ArrayList<>();
        while (!at(RPAREN)) {
            stmts.add(parseStatement());
            while (match(SEMI)) {
                // continua
            }
        }
        expect(RPAREN);
        return stmts;
    }

    // ------------------------------------------------------------------
    // expressões (Pratt)
    // ------------------------------------------------------------------

    private Expr parseExpr() {
        return parseExpr(0);
    }

    private Expr parseExpr(int minBp) {
        Expr lhs = parsePrefix();
        while (true) {
            lhs = applyPostfix(lhs);
            OpInfo op = binaryOpInfo();
            if (op == null) {
                return lhs;
            }
            if (op.prec < minBp) {
                return lhs;
            }
            pos += op.tokens();
            if (op.range) {
                int rp = op.prec + 1;
                Expr rhs = parseExpr(rp);
                lhs = new Expr.Range(lhs, rhs, op.inclusive, op.skipStart);
                continue;
            }
            int rp = op.rightAssoc ? op.prec : op.prec + 1;
            Expr rhs = parseExpr(rp);
            lhs = new Expr.Binary(op.op, lhs, rhs);
        }
    }

    /**
     * Operador infixo. {@code tokens} é quantos tokens o operador ocupa: 1 para
     * os normais e 2 para os compostos por duas palavras ({@code NOT IN}).
     */
    private record OpInfo(BinaryOp op, int prec, boolean rightAssoc, boolean range,
                          boolean inclusive, boolean skipStart, int tokens) {
    }

    private Expr applyPostfix(Expr base) {
        Token t = peek();
        if (t.is(DOT) || t.is(TokenType.LBRACKET)) {
            List<Part> parts = new ArrayList<>();
            while (true) {
                Token tk = peek();
                if (tk.is(DOT)) {
                    pos++;
                    parts.add(parseDotPart());
                } else if (tk.is(TokenType.LBRACKET)) {
                    pos++;
                    parts.add(parseBracketPart());
                } else {
                    break;
                }
            }
            return new Expr.Idiom(base, parts);
        }
        if (t.is(LARROW) || t.is(ARROW) || t.is(B_ARROW)) {
            pos++;
            Direction dir = t.is(LARROW) ? Direction.IN : t.is(B_ARROW) ? Direction.BOTH : Direction.OUT;
            return new Expr.Idiom(base, List.of(new Part.Graph(dir, parseLookup())));
        }
        return base;
    }

    private Part parseDotPart() {
        if (match(STAR)) {
            return new Part.All();
        }
        if (match(TokenType.QMARK)) {
            return new Part.First();
        }
        if (match(TokenType.QMARK_COLON)) {
            return new Part.Last();
        }
        String name = expectIdent();
        if (at(LPAREN)) {
            return new Part.Method(name, parseArgs());
        }
        return new Part.Field(name);
    }

    private Part parseBracketPart() {
        if (matchKeyword("where")) {
            Expr cond = parseExpr();
            expect(TokenType.RBRACKET);
            return new Part.Where(cond);
        }
        if (match(STAR)) {
            expect(TokenType.RBRACKET);
            return new Part.All();
        }
        Expr idx = parseExpr();
        expect(TokenType.RBRACKET);
        return new Part.Index(idx);
    }

    private Expr parseLookup() {
        if (match(STAR)) {
            return new Expr.Ident("*");
        }
        if (at(LPAREN)) {
            pos++;
            return new Expr.SubQuery(parseNestedQuery());
        }
        return parseTarget();
    }

    private OpInfo binaryOpInfo() {
        Token t = peek();
        return switch (t.type()) {
            case QUESTION_QUESTION -> new OpInfo(BinaryOp.COALESCE, 1, false, false, false, false, 1);
            case QMARK_COLON -> new OpInfo(BinaryOp.TERNARY, 1, false, false, false, false, 1);
            case TokenType.OR_OR -> new OpInfo(BinaryOp.OR, 2, false, false, false, false, 1);
            case TokenType.AND_AND -> new OpInfo(BinaryOp.AND, 3, false, false, false, false, 1);
            case EQ -> new OpInfo(BinaryOp.EQ, 4, false, false, false, false, 1);
            case EQ_EQ -> new OpInfo(BinaryOp.EQ_EXACT, 4, false, false, false, false, 1);
            case NE -> new OpInfo(BinaryOp.NE, 4, false, false, false, false, 1);
            case STAR_EQ -> new OpInfo(BinaryOp.ALL_EQ, 4, false, false, false, false, 1);
            case TokenType.QMARK_EQ -> new OpInfo(BinaryOp.ANY_EQ, 4, false, false, false, false, 1);
            case TokenType.MATCH -> new OpInfo(BinaryOp.MATCH, 5, false, false, false, false, 1);
            case LT -> new OpInfo(BinaryOp.LT, 5, false, false, false, false, 1);
            case GT -> new OpInfo(BinaryOp.GT, 5, false, false, false, false, 1);
            case LE -> new OpInfo(BinaryOp.LE, 5, false, false, false, false, 1);
            case GE -> new OpInfo(BinaryOp.GE, 5, false, false, false, false, 1);
            case DOT_DOT -> new OpInfo(BinaryOp.ADD, 5, false, true, true, false, 1);
            case DOT_DOT_EQ -> new OpInfo(BinaryOp.ADD, 5, false, true, true, false, 1);
            case GT_DOT_DOT -> new OpInfo(BinaryOp.ADD, 5, false, true, true, true, 1);
            case GT_DOT_DOT_EQ -> new OpInfo(BinaryOp.ADD, 5, false, true, true, true, 1);
            case PLUS -> new OpInfo(BinaryOp.ADD, 6, false, false, false, false, 1);
            case MINUS -> new OpInfo(BinaryOp.SUB, 6, false, false, false, false, 1);
            case STAR -> new OpInfo(BinaryOp.MUL, 7, false, false, false, false, 1);
            case SLASH -> new OpInfo(BinaryOp.DIV, 7, false, false, false, false, 1);
            case PERCENT -> new OpInfo(BinaryOp.MOD, 7, false, false, false, false, 1);
            case STAR_STAR -> new OpInfo(BinaryOp.POW, 8, true, false, false, false, 1);
            case KEYWORD -> keywordOpInfo(t);
            default -> null;
        };
    }

    private OpInfo keywordOpInfo(Token t) {
        if (t.isKeyword("and")) {
            return new OpInfo(BinaryOp.AND, 3, false, false, false, false, 1);
        }
        if (t.isKeyword("or")) {
            return new OpInfo(BinaryOp.OR, 2, false, false, false, false, 1);
        }
        if (t.isKeyword("contains")) {
            return new OpInfo(BinaryOp.CONTAIN, 5, false, false, false, false, 1);
        }
        if (t.isKeyword("in")) {
            return new OpInfo(BinaryOp.INSIDE, 5, false, false, false, false, 1);
        }
        if (t.isKeyword("not")) {
            // operadores de duas palavras: NOT IN e NOT CONTAINS
            Token next = peek(1);
            if (next.isKeyword("in")) {
                return new OpInfo(BinaryOp.NOT_INSIDE, 5, false, false, false, false, 2);
            }
            if (next.isKeyword("contains")) {
                return new OpInfo(BinaryOp.NOT_CONTAIN, 5, false, false, false, false, 2);
            }
            return null;
        }
        if (t.isKeyword("inside")) {
            return new OpInfo(BinaryOp.INSIDE, 5, false, false, false, false, 1);
        }
        if (t.isKeyword("outside")) {
            return new OpInfo(BinaryOp.OUTSIDE, 5, false, false, false, false, 1);
        }
        if (t.isKeyword("intersects")) {
            return new OpInfo(BinaryOp.INTERSECTS, 5, false, false, false, false, 1);
        }
        return null;
    }

    // ------------------------------------------------------------------
    // prefixo e primário
    // ------------------------------------------------------------------

    private Expr parsePrefix() {
        Token t = peek();
        switch (t.type()) {
            case MINUS -> {
                pos++;
                return new Expr.Unary(UnaryOp.NEG, parseExpr(9));
            }
            case PLUS -> {
                pos++;
                return new Expr.Unary(UnaryOp.POS, parseExpr(9));
            }
            case BANG -> {
                pos++;
                return new Expr.Unary(UnaryOp.NOT, parseExpr(9));
            }
            case ARROW, LARROW, B_ARROW -> {
                pos++;
                Expr.Direction dir = t.is(ARROW) ? Expr.Direction.OUT
                    : t.is(B_ARROW) ? Expr.Direction.BOTH : Expr.Direction.IN;
                List<Expr.Part> parts = new ArrayList<>();
                parts.add(new Expr.Part.Graph(dir, parseLookup()));
                return new Expr.Idiom(new Expr.Ident("*"), parts);
            }
            case LT -> {
                if (isCastAhead()) {
                    pos++;
                    String type = expectTypeWord();
                    expect(TokenType.GT);
                    return new Expr.Cast(type, parseExpr(9));
                }
            }
            case KEYWORD -> {
                if (t.isKeyword("not")) {
                    pos++;
                    return new Expr.Unary(UnaryOp.NOT, parseExpr(9));
                }
            }
            default -> {
            }
        }
        if (t.isKeyword("true")) {
            pos++;
            return new Expr.Literal(AxonValue.bool(true));
        }
        if (t.isKeyword("false")) {
            pos++;
            return new Expr.Literal(AxonValue.bool(false));
        }
        if (t.isKeyword("null")) {
            pos++;
            return new Expr.Literal(AxonValue.nul());
        }
        if (t.isKeyword("none")) {
            pos++;
            return new Expr.Literal(AxonValue.none());
        }
        return parsePrimary();
    }

    private boolean isCastAhead() {
        Token t1 = peek(1);
        Token t2 = peek(2);
        return (t1.is(IDENT) || t1.is(KEYWORD)) && t2.is(TokenType.GT);
    }

    private Expr parsePrimary() {
        Token t = peek();
        return switch (t.type()) {
            case INT, FLOAT, DECIMAL, STRING, DATETIME -> literalValue(take());
            case TokenType.UUID -> literalUuid(take());
            case BYTES -> literalBytes(take());
            case DURATION -> parseDuration();
            case PARAM -> new Expr.Param((String) take().literal());
            case IDENT -> primaryIdent();
            case KEYWORD -> primaryKeyword();
            case STAR -> {
                take();
                yield new Expr.Ident("*");
            }
            case LPAREN -> coveredOrSubquery();
            case LBRACE -> parseObjectOrSet();
            case LBRACKET -> parseArrayLiteral();
            default -> throw error(Messages.get("parser_unexpected_expression", t.start(), t.text()));
        };
    }

    private Expr primaryKeyword() {
        Token name = take();
        // Subquery: SELECT ... sem parenteses, mas sem consumir ')'
        if (name.isKeyword("select")) {
            List<Statement> stmts = new ArrayList<>();
            stmts.add(parseSelectBody());
            return new Expr.SubQuery(new Query(stmts));
        }
        // nomes de função com namespace começam por palavras que também são
        // palavras-chave de tipo: string::, array::, object::, type::, record::
        if (at(COLON_COLON)) {
            return functionPath(name.text());
        }
        // Palavras-chave também podem ser nomes de tabela em um record id
        // (por exemplo, user:1), como ocorre no SurrealQL.
        if (at(COLON)) {
            pos++;
            return new Expr.RecordId(new Expr.Ident(name.text()), parseRecordKey());
        }
        // keywords de agregação e funções tratadas como nomes de função
        if (at(LPAREN)) {
            return new Expr.Call(name.text(), parseArgs());
        }
        return new Expr.Ident(name.text());
    }

    /** Caminho com namespace: {@code a::b::c}, com ou sem argumentos. */
    private Expr functionPath(String head) {
        StringBuilder sb = new StringBuilder(head);
        while (at(COLON_COLON)) {
            pos++;
            sb.append("::").append(expectIdent());
        }
        String path = sb.toString();
        if (at(LPAREN)) {
            return new Expr.Call(path, parseArgs());
        }
        return new Expr.Path(path);
    }

    private Expr primaryIdent() {
        Token name = take();
        if (at(COLON_COLON)) {
            return functionPath(name.text());
        }
        if (at(LPAREN)) {
            return new Expr.Call(name.text(), parseArgs());
        }
        if (at(COLON)) {
            pos++;
            Expr key = parseRecordKey();
            return new Expr.RecordId(new Expr.Ident(name.text()), key);
        }
        return new Expr.Ident(name.text());
    }

    private Expr coveredOrSubquery() {
        pos++;
        if (atKeyword("select")) {
            return new Expr.SubQuery(parseNestedQuery());
        }
        Expr inner = parseExpr();
        expect(RPAREN);
        return inner;
    }

    private Query parseNestedQuery() {
        List<Statement> stmts = new ArrayList<>();
        while (!at(RPAREN)) {
            stmts.add(parseStatement());
            while (match(SEMI)) {
                // continua
            }
        }
        expect(RPAREN);
        return new Query(stmts);
    }

    private Expr parseDuration() {
        long millis = (Long) take().literal();
        while (at(DURATION)) {
            millis += (Long) take().literal();
        }
        return new Expr.Literal(AxonValue.duration(millis));
    }

    private List<Expr> parseArgs() {
        expect(LPAREN);
        List<Expr> args = new ArrayList<>();
        if (!at(RPAREN)) {
            args.add(parseExpr());
            while (match(COMMA)) {
                args.add(parseExpr());
            }
        }
        expect(RPAREN);
        return args;
    }

    private Expr literalValue(Token t) {
        return switch (t.type()) {
            case INT -> new Expr.Literal(AxonValue.num((Long) t.literal()));
            case FLOAT -> new Expr.Literal(AxonValue.num((Double) t.literal()));
            case DECIMAL -> new Expr.Literal(AxonValue.num((BigDecimal) t.literal()));
            case DATETIME -> new Expr.Literal(AxonValue.datetime(Instant.parse((String) t.literal())));
            case STRING -> new Expr.Literal(AxonValue.str((String) t.literal()));
            default -> throw error(Messages.get("parser_invalid_literal", t.start()));
        };
    }

    private Expr literalUuid(Token t) {
        return new Expr.Literal(AxonValue.uuid(UUID.fromString((String) t.literal())));
    }

    private Expr literalBytes(Token t) {
        return new Expr.Literal(AxonValue.bytes(java.util.HexFormat.of().parseHex((String) t.literal())));
    }

    private Expr parseArrayLiteral() {
        expect(TokenType.LBRACKET);
        List<Expr> items = new ArrayList<>();
        if (!at(TokenType.RBRACKET)) {
            items.add(parseExpr());
            while (match(COMMA)) {
                items.add(parseExpr());
            }
        }
        expect(TokenType.RBRACKET);
        return new Expr.ArrayLit(items);
    }

    private Expr parseObjectOrSet() {
        expect(TokenType.LBRACE);
        if (at(TokenType.RBRACE)) {
            take();
            return new Expr.ObjectLit(List.of());
        }
        if (peek().is(KEYWORD) && isStatementKeyword(peek())) {
            return parseBlockBody();
        }
        if (isObjectLiteralAhead()) {
            return parseObjectBody();
        }
        List<Expr> items = new ArrayList<>();
        items.add(parseExpr());
        while (match(COMMA)) {
            items.add(parseExpr());
        }
        expect(TokenType.RBRACE);
        return new Expr.SetLit(items);
    }

    /** Verifica se o padrão é DDL (ex.: {@code CREATE INDEX <name> ON ...}) sem consumir tokens. */
    private boolean peekDdlCreate(String keyword, String expectedAfterName) {
        if (!atKeyword(keyword)) return false;
        Token name = peek(1);
        if (name == null || !(name.is(IDENT) || name.is(TokenType.STRING) || name.is(KEYWORD))) return false;
        if (expectedAfterName == null) return true;
        Token after = peek(2);
        return after != null && after.isKeyword(expectedAfterName);
    }

    /** {@code CREATE ANALYZER <name> [option1|option2|...] } */
    private boolean peekDdlCreate(String keyword, String ignored, String... ddlKeywords) {
        if (!atKeyword(keyword)) return false;
        Token name = peek(1);
        if (name == null || !(name.is(IDENT) || name.is(TokenType.STRING) || name.is(KEYWORD))) return false;
        Token after = peek(2);
        if (after == null) return false;
        for (String kw : ddlKeywords) {
            if (after.isKeyword(kw)) return true;
        }
        return false;
    }

    private boolean isStatementKeyword(Token t) {
        return t.isKeyword("select") || t.isKeyword("create") || t.isKeyword("update")
            || t.isKeyword("delete") || t.isKeyword("insert") || t.isKeyword("define")
            || t.isKeyword("let") || t.isKeyword("return") || t.isKeyword("if")
            || t.isKeyword("for") || t.isKeyword("info") || t.isKeyword("relate")
            || t.isKeyword("begin") || t.isKeyword("commit") || t.isKeyword("cancel")
            || t.isKeyword("alter") || t.isKeyword("drop") || t.isKeyword("grant")
            || t.isKeyword("revoke");
    }

    private boolean isObjectLiteralAhead() {
        Token t1 = peek();
        Token t2 = peek(1);
        if (t1.is(IDENT)) {
            return t2.is(COLON);
        }
        if (t1.is(STRING)) {
            return t2.is(COLON);
        }
        if (t1.is(KEYWORD) && !isStatementKeyword(t1)) {
            return t2.is(COLON);
        }
        return false;
    }

    private Expr parseObjectBody() {
        List<Expr.ObjectLit.Entry> entries = new ArrayList<>();
        while (true) {
            String key = parseObjectKey();
            expect(COLON);
            Expr value = parseExpr();
            entries.add(new Expr.ObjectLit.Entry(key, value));
            if (!match(COMMA)) {
                break;
            }
        }
        expect(TokenType.RBRACE);
        return new Expr.ObjectLit(entries);
    }

    private String parseObjectKey() {
        Token t = peek();
        if (t.is(STRING)) {
            return (String) take().literal();
        }
        return expectIdent();
    }

    private Expr parseBlockBody() {
        List<Statement> stmts = new ArrayList<>();
        while (!at(TokenType.RBRACE)) {
            stmts.add(parseStatement());
            while (match(SEMI)) {
                // continua
            }
        }
        expect(TokenType.RBRACE);
        return new Expr.Block(stmts);
    }
}
