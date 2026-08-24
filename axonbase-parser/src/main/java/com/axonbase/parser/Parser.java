package com.axonbase.parser;

import com.axonbase.common.AxonError;
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

    private Token expect(TokenType t) {
        Token tok = peek();
        if (!tok.is(t)) {
            throw error("esperava-se " + t + " na posição " + tok.start()
                + " mas apareceu '" + tok.text() + "'");
        }
        return take();
    }

    private Token expectKeyword(String kw) {
        if (!atKeyword(kw)) {
            throw error("esperava-se a palavra-chave '" + kw + "' na posição " + peek().start());
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
            default -> throw error("sentença inesperada na posição " + t.start() + ": '" + t.text() + "'");
        };
    }

    private Statement statementKeyword(Token t) {
        if (t.isKeyword("use")) {
            return parseUse();
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
        if (t.isKeyword("live")) {
            return parseLive();
        }
        if (t.isKeyword("relate")) {
            return parseRelate();
        }
        if (t.isKeyword("define")) {
            return parseDefine();
        }
        if (t.isKeyword("info")) {
            return parseInfo();
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
            pos++;
            return new Statement.Begin();
        }
        if (t.isKeyword("commit")) {
            pos++;
            return new Statement.Commit();
        }
        if (t.isKeyword("cancel")) {
            pos++;
            return new Statement.Cancel();
        }
        throw error("sentença inesperada na posição " + t.start() + ": '" + t.text() + "'");
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

    private Statement parseInsert() {
        pos++;
        boolean ignore = matchKeyword("ignore");
        boolean relation = matchKeyword("relation");
        expectKeyword("into");
        String table = expectIdent();
        Expr data = parseExpr();
        ReturnSpec ret = parseReturnSpec();
        return new Statement.Insert(ignore, relation, table, data, ret);
    }

    private Statement parseUpdate(UpdateMode mode) {
        pos++;
        boolean only = matchKeyword("only");
        Expr target = parseTarget();
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
        Expr cond = null;
        if (matchKeyword("where")) {
            cond = parseExpr();
        }
        ReturnSpec ret = parseReturnSpec();
        return new Statement.Delete(target, cond, ret);
    }

    private Statement parseSelect() {
        pos++;
        // SELECT VALUE <expr> devolve os valores nus, sem envolver num objeto
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
        expectKeyword("from");
        Expr from = parseTarget();
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
            limit, start, fetch);
    }

    /** LIVE SELECT ... [DIFF]: consome 'live' e delega no parseSelect. */
    private Statement parseLive() {
        pos++; // live
        boolean diff = false;
        Statement parsed = parseSelect();
        if (!(parsed instanceof Statement.Select sel)) {
            throw error("LIVE SELECT esperava um SELECT");
        }
        if (matchKeyword("diff")) {
            diff = true;
        }
        return new Statement.Live(sel, diff);
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
                    default -> throw error("acción de permiso non recoñecida: " + action);
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
            String name = expectIdent();
            AuthTarget target = parseAuthTarget();
            Expr password = null;
            String passhash = null;
            if (matchKeyword("passhash")) {
                passhash = (String) expect(TokenType.STRING).literal();
            } else {
                expectKeyword("password");
                password = parseExpr();
            }
            List<String> roles = new ArrayList<>();
            if (matchKeyword("roles")) {
                roles.add(expectIdent());
                while (match(COMMA)) {
                    roles.add(expectIdent());
                }
            }
            return new Statement.DefineUser(name, target.scope(), target.namespace(),
                target.database(), password, passhash, roles);
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
            return new Statement.DefineField(name, table, type, assertE, readonly, valueE, defaultE);
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
            Integer vectorDimension = null;
            String vectorDistance = null;
            if (!unique && !count && matchKeyword("search")) {
                expectKeyword("analyzer");
                searchAnalyzer = expectIdent();
            } else if (!unique && !count && matchKeyword("geo")) {
                geo = true;
            } else if (!unique && !count && matchKeyword("hnsw")) {
                expectKeyword("dimension");
                vectorDimension = ((Number) expect(TokenType.INT).literal()).intValue();
                if (matchKeyword("dist")) {
                    vectorDistance = expectIdent();
                } else {
                    vectorDistance = "euclidean";
                }
            }
            return new Statement.DefineIndex(name, table, columns, unique, count, searchAnalyzer,
                geo, vectorDimension, vectorDistance);
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
        throw error("DEFINE inesperado na posição " + peek().start() + ": '" + peek().text() + "'");
    }

    /** Escopo de DEFINE USER/ACCESS: ON ROOT, ON NAMESPACE [nome], ON DATABASE [nome]. */
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
            String db = at(IDENT) ? expectIdent() : null;
            return new AuthTarget(Statement.AuthScope.DATABASE, null, db);
        }
        throw error("DEFINE USER/ACCESS exige ON ROOT, ON NAMESPACE ou ON DATABASE");
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
        if (matchKeyword("database")) {
            return new Statement.Info("database", null);
        }
        if (matchKeyword("table")) {
            return new Statement.Info("table", expectIdent());
        }
        throw error("INFO inválido na posição " + peek().start());
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
            return String.valueOf(take().literal() instanceof AxonValue av && av.isString()
                ? av.asString() : t.text());
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
        String name = expectIdent();
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
            default -> throw error("chave de record inválida na posição " + t.start() + ": '" + t.text() + "'");
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
            default -> throw error("expressão inesperada na posição " + t.start() + ": '" + t.text() + "'");
        };
    }

    private Expr primaryKeyword() {
        Token name = take();
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
            default -> throw error("literal inválido na posição " + t.start());
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

    private boolean isStatementKeyword(Token t) {
        return t.isKeyword("select") || t.isKeyword("create") || t.isKeyword("update")
            || t.isKeyword("delete") || t.isKeyword("insert") || t.isKeyword("define")
            || t.isKeyword("let") || t.isKeyword("return") || t.isKeyword("if")
            || t.isKeyword("for") || t.isKeyword("info") || t.isKeyword("relate")
            || t.isKeyword("begin") || t.isKeyword("commit") || t.isKeyword("cancel");
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
