package com.axonbase.pl.parse;

import com.axonbase.pl.PlConstantInfo;
import com.axonbase.pl.PlFunctionInfo;
import com.axonbase.pl.PlImportInfo;
import com.axonbase.pl.PlParamInfo;
import com.axonbase.pl.ast.Const;
import com.axonbase.pl.ast.Expr;
import com.axonbase.pl.ast.Flow;
import com.axonbase.pl.ast.FunctionCode;
import com.axonbase.pl.ast.ImportStmts;
import com.axonbase.pl.ast.MakeFunction;
import com.axonbase.pl.ast.Name;
import com.axonbase.pl.ast.Scope;
import com.axonbase.pl.ast.SimpleStmts;
import com.axonbase.pl.ast.Stmt;
import com.axonbase.pl.ast.Subscript;
import com.axonbase.pl.ast.Target;
import com.axonbase.pl.ast.TryStmt;
import com.axonbase.pl.lex.Lexer;
import com.axonbase.pl.lex.Token;
import com.axonbase.pl.lex.Token.T;
import com.axonbase.pl.rt.Ops;
import java.util.ArrayList;
import java.util.List;

/** Analisador sintatico de comandos do AxonPL (descida recursiva). */
public final class Parser extends ExprParser {

    /** Resultado da analise de um programa. */
    public record Resultado(FunctionCode modulo, String[] globais, List<PlFunctionInfo> funcoes,
                            List<PlConstantInfo> constantes, List<PlImportInfo> imports) {
    }

    private final List<PlFunctionInfo> funcs = new ArrayList<>();
    private final List<PlConstantInfo> consts = new ArrayList<>();
    private final List<PlImportInfo> imports = new ArrayList<>();

    private Parser(String src, List<Token> toks) {
        super(src, toks);
    }

    /** Analisa o texto (ja normalizado) e devolve o modulo resolvido. */
    public static Resultado analisar(String normalizedSource) {
        Lexer lx = new Lexer(normalizedSource);
        List<Token> toks = lx.tokenize();
        Parser p = new Parser(normalizedSource, toks);
        return p.module();
    }

    private Resultado module() {
        FunctionCode mc = newCode("<modulo>", 1);
        mc.isModule = true;
        module = new Scope(null, true, mc);
        cur = module;
        List<Stmt> body = new ArrayList<>();
        while (peek().type != T.EOF) {
            body.addAll(statement());
        }
        mc.body = body.toArray(new Stmt[0]);
        String[] globais = Resolver.resolve(module);
        return new Resultado(mc, globais, funcs, consts, imports);
    }

    // ------------------------------------------------------------------ comandos

    private List<Stmt> statement() {
        Token t = peek();
        if (t.type == T.NEWLINE) {
            next();
            return List.of();
        }
        if (t.type == T.INDENT) {
            throw err(t, "indentacao inesperada");
        }
        if (t.type == T.DEDENT) {
            throw err(t, "recuo inconsistente");
        }
        if (t.type == T.NAME) {
            switch (t.text) {
                case "if":
                    return List.of(ifStmt());
                case "while":
                    return List.of(whileStmt());
                case "for":
                    return List.of(forStmt());
                case "def":
                    return List.of(funcDef());
                case "try":
                    return List.of(tryStmt());
                case "class":
                    throw unsupported(t, "a definicao de classes (class)");
                case "with":
                    throw unsupported(t, "o comando with");
                case "async":
                case "await":
                    throw unsupported(t, "async/await");
                case "elif":
                case "else":
                case "except":
                case "finally":
                    throw err(t, "'" + t.text + "' sem o comando de abertura correspondente");
                case "match":
                    if (looksLikeMatch()) {
                        throw unsupported(t, "o comando match");
                    }
                    break;
                default:
                    break;
            }
        }
        if (t.isOp("@")) {
            throw unsupported(t, "decoradores (@)");
        }
        return simpleLine();
    }

    private boolean looksLikeMatch() {
        int j = i;
        while (toks.get(j).type != T.NEWLINE && toks.get(j).type != T.EOF) {
            j++;
        }
        Token n = peek(1);
        boolean notOp = !(n.isOp("=") || n.isOp(".") || n.isOp(",") || n.isOp(")") || n.isOp(":"));
        return j > 0 && toks.get(j - 1).isOp(":") && notOp && n.type != T.NEWLINE;
    }

    private List<Stmt> simpleLine() {
        List<Stmt> out = new ArrayList<>();
        out.add(simple());
        while (acceptOp(";")) {
            if (peek().type == T.NEWLINE || peek().type == T.EOF) {
                break;
            }
            out.add(simple());
        }
        Token t = peek();
        if (t.type == T.NEWLINE) {
            next();
        } else if (t.type != T.EOF) {
            throw err(t, "sintaxe invalida: '" + (t.text.isEmpty() ? t.type.name() : t.text) + "' inesperado");
        }
        return out;
    }

    private Stmt[] block() {
        expectOp(":", "no fim do cabecalho");
        List<Stmt> out = new ArrayList<>();
        if (peek().type == T.NEWLINE) {
            next();
            if (peek().type != T.INDENT) {
                throw err(peek(), "esperado um bloco indentado");
            }
            next();
            while (peek().type != T.DEDENT && peek().type != T.EOF) {
                out.addAll(statement());
            }
            if (peek().type == T.DEDENT) {
                next();
            }
        } else {
            out.addAll(simpleLine());
        }
        return out.toArray(new Stmt[0]);
    }

    private Stmt ifStmt() {
        Token t = next();
        Expr cond = expression();
        Stmt[] body = block();
        Stmt[] orelse = new Stmt[0];
        if (isName("elif")) {
            orelse = new Stmt[] {ifStmt2()};
        } else if (isName("else")) {
            next();
            orelse = block();
        }
        return new Flow.If(cond, body, orelse).at(t.line, t.col);
    }

    private Stmt ifStmt2() {
        return ifStmt();
    }

    private Stmt whileStmt() {
        Token t = next();
        Expr cond = expression();
        Stmt[] body = block();
        Stmt[] orelse = new Stmt[0];
        if (acceptName("else")) {
            orelse = block();
        }
        return new Flow.While(cond, body, orelse).at(t.line, t.col);
    }

    private Stmt forStmt() {
        Token t = next();
        Target target = toTarget(forTargets());
        expectName("in", "no comando for");
        Expr iter = starExprs();
        Stmt[] body = block();
        Stmt[] orelse = new Stmt[0];
        if (acceptName("else")) {
            orelse = block();
        }
        return new Flow.For(target, iter, body, orelse).at(t.line, t.col);
    }

    private Stmt funcDef() {
        Token d = next();
        Token nameTok = peek();
        String name = ident("depois de 'def'");
        Name target = newName(name, nameTok);
        bind(name);
        expectOp("(", "depois do nome da funcao");
        Params ps = params(")", true);
        expectOp(")", "para fechar os parametros");
        String ret = null;
        if (acceptOp("->")) {
            int refMark = cur.refs.size();
            int childMark = cur.children.size();
            int st = i;
            expression();
            ret = textFrom(st);
            while (cur.refs.size() > refMark) {
                cur.refs.remove(cur.refs.size() - 1);
            }
            while (cur.children.size() > childMark) {
                cur.children.remove(cur.children.size() - 1);
            }
        }
        FunctionCode code = newCode(name, d.line);
        Scope parent = cur;
        Scope s = new Scope(parent, false, code);
        ps.install(code, s, this);
        cur = s;
        Stmt[] body = block();
        cur = parent;
        code.body = body;
        if (body.length > 0 && body[0] instanceof SimpleStmts.ExprStmt es && es.e instanceof Const c
                && c.value instanceof String doc) {
            code.doc = doc;
        }
        if (parent.module) {
            List<PlParamInfo> infos = new ArrayList<>();
            for (int k = 0; k < ps.order.size(); k++) {
                PlParamInfo.Tipo tp = switch (ps.kinds.get(k)) {
                    case "kwonly" -> PlParamInfo.Tipo.SOMENTE_NOMEADO;
                    case "args" -> PlParamInfo.Tipo.ARGS;
                    case "kwargs" -> PlParamInfo.Tipo.KWARGS;
                    default -> PlParamInfo.Tipo.POSICIONAL;
                };
                infos.add(new PlParamInfo(ps.order.get(k), ps.defaultTexts.get(k), ps.annotations.get(k), tp));
            }
            funcs.add(new PlFunctionInfo(name, infos, ret, code.doc, d.line));
        }
        return new Flow.FuncDef(target, new MakeFunction(code).at(d.line, d.col)).at(d.line, d.col);
    }

    private Stmt tryStmt() {
        Token t = next();
        Stmt[] body = block();
        List<TryStmt.Handler> hs = new ArrayList<>();
        while (isName("except")) {
            Token et = next();
            if (isOp("*")) {
                throw unsupported(et, "except*");
            }
            Expr type = null;
            Name nm = null;
            if (!isOp(":")) {
                type = expression();
                if (acceptName("as")) {
                    Token nt = peek();
                    String id = ident("depois de 'as'");
                    nm = newName(id, nt);
                    bind(id);
                }
            }
            hs.add(new TryStmt.Handler(type, nm, block()));
        }
        Stmt[] orelse = new Stmt[0];
        Stmt[] fin = new Stmt[0];
        if (!hs.isEmpty() && acceptName("else")) {
            orelse = block();
        }
        if (acceptName("finally")) {
            fin = block();
        }
        if (hs.isEmpty() && fin.length == 0) {
            throw err(t, "o comando try precisa de pelo menos um except ou um finally");
        }
        return new TryStmt(body, hs.toArray(new TryStmt.Handler[0]), orelse, fin).at(t.line, t.col);
    }

    // ------------------------------------------------------------------ comandos simples

    private Stmt simple() {
        Token t = peek();
        if (t.type == T.NAME) {
            switch (t.text) {
                case "pass":
                    next();
                    return new SimpleStmts.Pass().at(t.line, t.col);
                case "break":
                    next();
                    return new SimpleStmts.Break().at(t.line, t.col);
                case "continue":
                    next();
                    return new SimpleStmts.Continue().at(t.line, t.col);
                case "return": {
                    next();
                    Expr v = null;
                    if (peek().type != T.NEWLINE && peek().type != T.EOF && !isOp(";")) {
                        v = starExprs();
                    }
                    return new SimpleStmts.Return(v).at(t.line, t.col);
                }
                case "raise":
                    return raise(t);
                case "assert": {
                    next();
                    Expr c = expression();
                    Expr m = acceptOp(",") ? expression() : null;
                    return new SimpleStmts.Assert(c, m).at(t.line, t.col);
                }
                case "del": {
                    next();
                    Expr e = starExprs();
                    List<Target> ts = new ArrayList<>();
                    if (e instanceof com.axonbase.pl.ast.SeqLit sl && sl.kind == 't') {
                        for (Expr x : sl.items) {
                            ts.add(toTarget(x));
                        }
                    } else {
                        ts.add(toTarget(e));
                    }
                    return new SimpleStmts.Del(ts.toArray(new Target[0])).at(t.line, t.col);
                }
                case "import":
                    return importStmt();
                case "from":
                    return fromStmt();
                case "global":
                case "nonlocal":
                    return scopeDecl();
                case "yield":
                    throw unsupported(t, "yield (geradores)");
                case "class":
                    throw unsupported(t, "a definicao de classes (class)");
                case "with":
                    throw unsupported(t, "o comando with");
                default:
                    break;
            }
        }
        return exprOrAssign();
    }

    private Stmt raise(Token t) {
        next();
        Expr e = null;
        Expr cause = null;
        boolean hasCause = false;
        if (peek().type != T.NEWLINE && peek().type != T.EOF && !isOp(";")) {
            e = expression();
            if (acceptName("from")) {
                hasCause = true;
                cause = expression();
            }
        }
        return new TryStmt.Raise(e, cause, hasCause).at(t.line, t.col);
    }

    private Stmt scopeDecl() {
        Token t = next();
        boolean glob = t.text.equals("global");
        do {
            String n = ident("na declaracao " + t.text);
            if (!cur.module) {
                if (glob) {
                    cur.globals.add(n);
                    module.bound.add(n);
                } else {
                    cur.nonlocals.add(n);
                }
            }
        } while (acceptOp(","));
        return new SimpleStmts.Pass().at(t.line, t.col);
    }

    private Stmt importStmt() {
        Token t = next();
        List<Stmt> out = new ArrayList<>();
        do {
            Token mt = peek();
            String mod = dotted();
            String alias = null;
            if (acceptName("as")) {
                alias = ident("depois de 'as'");
            }
            if (mod.indexOf('.') >= 0 && alias == null) {
                throw unsupported(mt, "'import a.b' sem 'as' (use 'from a import b' ou 'import a.b as c')");
            }
            String bindName = alias != null ? alias : mod;
            Name n = newName(bindName, mt);
            bind(bindName);
            imports.add(new PlImportInfo(mod, List.of(), alias, t.line));
            out.add(new ImportStmts.Import(mod, n).at(t.line, t.col));
        } while (acceptOp(","));
        return out.size() == 1 ? out.get(0) : new Flow.If(new Const(Boolean.TRUE), out.toArray(new Stmt[0]),
                new Stmt[0]).at(t.line, t.col);
    }

    private String dotted() {
        StringBuilder sb = new StringBuilder(ident("como nome de modulo"));
        while (isOp(".")) {
            next();
            sb.append('.').append(ident("depois do '.'"));
        }
        return sb.toString();
    }

    private Stmt fromStmt() {
        Token t = next();
        if (isOp(".") || isOp("...")) {
            throw unsupported(peek(), "import relativo");
        }
        String mod = dotted();
        expectName("import", "depois do nome do modulo");
        if (isOp("*")) {
            throw unsupported(peek(), "'from ... import *'");
        }
        boolean paren = acceptOp("(");
        List<String> names = new ArrayList<>();
        List<Name> targets = new ArrayList<>();
        List<String> aliases = new ArrayList<>();
        do {
            if (paren && isOp(")")) {
                break;
            }
            Token nt = peek();
            String n = ident("na lista de import");
            String alias = acceptName("as") ? ident("depois de 'as'") : null;
            names.add(n);
            aliases.add(alias);
            String bn = alias != null ? alias : n;
            targets.add(newName(bn, nt));
            bind(bn);
        } while (acceptOp(","));
        if (paren) {
            expectOp(")", "para fechar a lista de import");
        }
        imports.add(new PlImportInfo(mod, names, null, t.line));
        return new ImportStmts.ImportFrom(mod, names.toArray(new String[0]), targets.toArray(new Name[0]))
                .at(t.line, t.col);
    }

    private static int augOp(String s) {
        return switch (s) {
            case "+=" -> Ops.ADD;
            case "-=" -> Ops.SUB;
            case "*=" -> Ops.MUL;
            case "/=" -> Ops.TRUEDIV;
            case "//=" -> Ops.FLOORDIV;
            case "%=" -> Ops.MOD;
            case "**=" -> Ops.POW;
            case "<<=" -> Ops.LSHIFT;
            case ">>=" -> Ops.RSHIFT;
            case "&=" -> Ops.BITAND;
            case "|=" -> Ops.BITOR;
            case "^=" -> Ops.BITXOR;
            default -> -1;
        };
    }

    private Stmt exprOrAssign() {
        Token t = peek();
        int firstTok = i;
        Expr e = starExprs();
        Token o = peek();
        if (o.isOp(":") && (e instanceof Name || e instanceof Subscript || e instanceof com.axonbase.pl.ast.Attribute)) {
            next();
            int refMark = cur.refs.size();
            int childMark = cur.children.size();
            expression();
            while (cur.refs.size() > refMark) {
                cur.refs.remove(cur.refs.size() - 1);
            }
            while (cur.children.size() > childMark) {
                cur.children.remove(cur.children.size() - 1);
            }
            if (acceptOp("=")) {
                int vs = i;
                Expr v = starExprs();
                Target tg = toTarget(e);
                recordConst(e, vs, t);
                return new SimpleStmts.Assign(new Target[] {tg}, v).at(t.line, t.col);
            }
            toTarget(e);
            return new SimpleStmts.Pass().at(t.line, t.col);
        }
        if (o.isOp("=")) {
            List<Target> targets = new ArrayList<>();
            targets.add(toTarget(e));
            Expr value;
            int vs;
            while (true) {
                next();
                vs = i;
                Expr rhs = starExprs();
                if (isOp("=")) {
                    targets.add(toTarget(rhs));
                } else {
                    value = rhs;
                    break;
                }
            }
            if (targets.size() == 1) {
                recordConst(e, vs, t);
            }
            return new SimpleStmts.Assign(targets.toArray(new Target[0]), value).at(t.line, t.col);
        }
        if (o.type == T.OP && o.text.endsWith("=") && augOp(o.text) >= 0) {
            if (o.text.equals("@=")) {
                throw unsupported(o, "o operador de matriz @=");
            }
            if (!(e instanceof Name || e instanceof Subscript || e instanceof com.axonbase.pl.ast.Attribute)) {
                throw err(o, "alvo invalido para atribuicao aumentada");
            }
            if (e instanceof Name n) {
                bind(n.id);
            }
            next();
            Expr v = starExprs();
            return new SimpleStmts.AugAssign(e, augOp(o.text), v).at(t.line, t.col);
        }
        if (o.isOp("@=")) {
            throw unsupported(o, "o operador de matriz @=");
        }
        if (o.isOp(":=")) {
            throw unsupported(o, "o operador walrus (:=)");
        }
        if (firstTok < 0) {
            throw err(t, "sintaxe invalida");
        }
        return new SimpleStmts.ExprStmt(e).at(t.line, t.col);
    }

    private void recordConst(Expr target, int valueStartTok, Token stmtTok) {
        if (cur == module && target instanceof Name n && !n.id.startsWith("_")) {
            consts.add(new PlConstantInfo(n.id, stmtTok.line, textFrom(valueStartTok)));
        }
    }
}
