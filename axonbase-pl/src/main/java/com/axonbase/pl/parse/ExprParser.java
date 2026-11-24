package com.axonbase.pl.parse;

import com.axonbase.pl.PlSyntaxError;
import com.axonbase.pl.ast.Attribute;
import com.axonbase.pl.ast.BinOp;
import com.axonbase.pl.ast.BoolOp;
import com.axonbase.pl.ast.Call;
import com.axonbase.pl.ast.Comp;
import com.axonbase.pl.ast.CompAppend;
import com.axonbase.pl.ast.Compare;
import com.axonbase.pl.ast.Const;
import com.axonbase.pl.ast.DictLit;
import com.axonbase.pl.ast.Expr;
import com.axonbase.pl.ast.FString;
import com.axonbase.pl.ast.Flow;
import com.axonbase.pl.ast.FunctionCode;
import com.axonbase.pl.ast.IfExp;
import com.axonbase.pl.ast.MakeFunction;
import com.axonbase.pl.ast.MethodCall;
import com.axonbase.pl.ast.Name;
import com.axonbase.pl.ast.Scope;
import com.axonbase.pl.ast.SeqLit;
import com.axonbase.pl.ast.SliceExpr;
import com.axonbase.pl.ast.Starred;
import com.axonbase.pl.ast.Stmt;
import com.axonbase.pl.ast.Subscript;
import com.axonbase.pl.ast.Target;
import com.axonbase.pl.ast.TupleTarget;
import com.axonbase.pl.ast.UnaryOp;
import com.axonbase.pl.lex.Lexer;
import com.axonbase.pl.lex.Token;
import com.axonbase.pl.lex.Token.T;
import com.axonbase.pl.rt.NoneType;
import com.axonbase.pl.rt.Ops;
import com.axonbase.pl.rt.PyBytes;
import com.axonbase.pl.rt.PyTuple;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Analise de expressoes (precedencia crescente) e construcoes de escopo (lambda e compreensoes). */
abstract class ExprParser extends ParserBase implements FStrings.ExprSource {

    protected ExprParser(String src, List<Token> toks) {
        super(src, toks);
    }

    // ---------------------------------------------------------------- f-strings

    @Override
    public Expr parseExpr(String text, Token at) {
        try {
            Lexer lx = new Lexer("(" + text + ")");
            List<Token> ts = lx.tokenize();
            SubParser sp = new SubParser(src, ts, this);
            Expr e = sp.expression();
            if (sp.peek().type != T.NEWLINE && sp.peek().type != T.EOF) {
                throw new PlSyntaxError(at.line, at.col, "expressao invalida dentro da f-string", "");
            }
            return e;
        } catch (PlSyntaxError e) {
            throw err(at, "em f-string: " + e.mensagemSimples());
        }
    }

    @Override
    public PlSyntaxError error(Token at, String msg) {
        return err(at, msg);
    }

    /** Analisador de trechos de f-string: compartilha o escopo do analisador pai. */
    static final class SubParser extends ExprParser {
        SubParser(String src, List<Token> toks, ExprParser parent) {
            super(src, toks);
            this.cur = parent.cur;
            this.module = parent.module;
        }
    }

    // ---------------------------------------------------------------- expressoes

    protected final Expr expression() {
        Token t = peek();
        if (t.isName("lambda")) {
            return lambda();
        }
        Expr e = orTest();
        if (isName("if")) {
            next();
            Expr c = orTest();
            expectName("else", "na expressao condicional");
            Expr alt = expression();
            return new IfExp(c, e, alt).at(t.line, t.col);
        }
        return e;
    }

    protected final Expr starOrExpr() {
        if (isOp("*")) {
            Token t = next();
            return new Starred(bitOr()).at(t.line, t.col);
        }
        return expression();
    }

    /** star_expressions: lista separada por virgula que vira tupla. */
    protected final Expr starExprs() {
        Token t = peek();
        Expr first = starOrExpr();
        if (!isOp(",")) {
            return first;
        }
        List<Expr> items = new ArrayList<>();
        items.add(first);
        while (acceptOp(",")) {
            if (endOfExprList()) {
                break;
            }
            items.add(starOrExpr());
        }
        return new SeqLit('t', items.toArray(new Expr[0])).at(t.line, t.col);
    }

    private boolean endOfExprList() {
        Token t = peek();
        return t.type == T.NEWLINE || t.type == T.EOF || t.isOp("=") || t.isOp(")") || t.isOp("]") || t.isOp("}")
                || t.isOp(":") || t.isOp(";") || t.isName("in");
    }

    /** Alvos de for: elementos no nivel de bitwise-or para nao consumir o "in". */
    protected final Expr forTargets() {
        Token t = peek();
        Expr first = forTarget();
        if (!isOp(",")) {
            return first;
        }
        List<Expr> items = new ArrayList<>();
        items.add(first);
        while (acceptOp(",")) {
            if (isName("in")) {
                break;
            }
            items.add(forTarget());
        }
        return new SeqLit('t', items.toArray(new Expr[0])).at(t.line, t.col);
    }

    private Expr forTarget() {
        if (isOp("*")) {
            Token t = next();
            return new Starred(bitOr()).at(t.line, t.col);
        }
        return bitOr();
    }

    protected final Expr orTest() {
        Token t = peek();
        Expr e = andTest();
        if (!isName("or")) {
            return e;
        }
        List<Expr> parts = new ArrayList<>();
        parts.add(e);
        while (acceptName("or")) {
            parts.add(andTest());
        }
        return new BoolOp(false, parts.toArray(new Expr[0])).at(t.line, t.col);
    }

    private Expr andTest() {
        Token t = peek();
        Expr e = notTest();
        if (!isName("and")) {
            return e;
        }
        List<Expr> parts = new ArrayList<>();
        parts.add(e);
        while (acceptName("and")) {
            parts.add(notTest());
        }
        return new BoolOp(true, parts.toArray(new Expr[0])).at(t.line, t.col);
    }

    private Expr notTest() {
        Token t = peek();
        if (t.isName("not")) {
            next();
            return new UnaryOp('!', notTest()).at(t.line, t.col);
        }
        return comparison();
    }

    private Expr comparison() {
        Token t = peek();
        Expr first = bitOr();
        List<Integer> ops = new ArrayList<>();
        List<Expr> rest = new ArrayList<>();
        while (true) {
            Token o = peek();
            int op = -1;
            if (o.type == T.OP) {
                switch (o.text) {
                    case "<" -> op = Ops.LT;
                    case "<=" -> op = Ops.LE;
                    case ">" -> op = Ops.GT;
                    case ">=" -> op = Ops.GE;
                    case "==" -> op = Ops.EQ;
                    case "!=" -> op = Ops.NE;
                    default -> { }
                }
                if (op >= 0) {
                    next();
                }
            } else if (o.isName("in")) {
                next();
                op = Compare.IN;
            } else if (o.isName("not") && peek(1).isName("in")) {
                next();
                next();
                op = Compare.NOT_IN;
            } else if (o.isName("is")) {
                next();
                op = acceptName("not") ? Compare.IS_NOT : Compare.IS;
            }
            if (op < 0) {
                break;
            }
            ops.add(op);
            rest.add(bitOr());
        }
        if (ops.isEmpty()) {
            return first;
        }
        int[] oa = new int[ops.size()];
        for (int k = 0; k < oa.length; k++) {
            oa[k] = ops.get(k);
        }
        return new Compare(first, oa, rest.toArray(new Expr[0])).at(t.line, t.col);
    }

    protected final Expr bitOr() {
        Token t = peek();
        Expr e = bitXor();
        while (isOp("|")) {
            next();
            e = new BinOp(Ops.BITOR, e, bitXor()).at(t.line, t.col);
        }
        return e;
    }

    private Expr bitXor() {
        Token t = peek();
        Expr e = bitAnd();
        while (isOp("^")) {
            next();
            e = new BinOp(Ops.BITXOR, e, bitAnd()).at(t.line, t.col);
        }
        return e;
    }

    private Expr bitAnd() {
        Token t = peek();
        Expr e = shift();
        while (isOp("&")) {
            next();
            e = new BinOp(Ops.BITAND, e, shift()).at(t.line, t.col);
        }
        return e;
    }

    private Expr shift() {
        Token t = peek();
        Expr e = arith();
        while (isOp("<<") || isOp(">>")) {
            int op = next().text.equals("<<") ? Ops.LSHIFT : Ops.RSHIFT;
            e = new BinOp(op, e, arith()).at(t.line, t.col);
        }
        return e;
    }

    private Expr arith() {
        Token t = peek();
        Expr e = term();
        while (isOp("+") || isOp("-")) {
            int op = next().text.equals("+") ? Ops.ADD : Ops.SUB;
            e = new BinOp(op, e, term()).at(t.line, t.col);
        }
        return e;
    }

    private Expr term() {
        Token t = peek();
        Expr e = factor();
        while (true) {
            Token o = peek();
            int op;
            if (o.isOp("*")) {
                op = Ops.MUL;
            } else if (o.isOp("/")) {
                op = Ops.TRUEDIV;
            } else if (o.isOp("//")) {
                op = Ops.FLOORDIV;
            } else if (o.isOp("%")) {
                op = Ops.MOD;
            } else if (o.isOp("@")) {
                throw unsupported(o, "o operador de matriz @");
            } else {
                return e;
            }
            next();
            e = new BinOp(op, e, factor()).at(t.line, t.col);
        }
    }

    private Expr factor() {
        Token t = peek();
        if (t.isOp("-") || t.isOp("+") || t.isOp("~")) {
            next();
            if (t.isOp("-") && peek().type == T.INT && peek().value instanceof BigInteger) {
                next();
                return new Const(Long.MIN_VALUE).at(t.line, t.col);
            }
            Expr e = factor();
            if (e instanceof Const c && t.isOp("-")) {
                if (c.value instanceof Long l && l != Long.MIN_VALUE) {
                    return new Const(-l).at(t.line, t.col);
                }
                if (c.value instanceof Double d) {
                    return new Const(-d).at(t.line, t.col);
                }
            }
            return new UnaryOp(t.text.charAt(0), e).at(t.line, t.col);
        }
        return power();
    }

    private Expr power() {
        Token t = peek();
        Expr e = primary();
        if (isOp("**")) {
            next();
            return new BinOp(Ops.POW, e, factor()).at(t.line, t.col);
        }
        return e;
    }

    private Expr primary() {
        Token t = peek();
        Expr e = atom();
        while (true) {
            Token o = peek();
            if (o.isOp(".")) {
                next();
                Token n = peek();
                if (n.type != T.NAME) {
                    throw err(n, "esperado o nome de um atributo depois do '.'");
                }
                next();
                if (isOp("(")) {
                    next();
                    CallArgs ca = callArgs();
                    e = new MethodCall(e, n.text, ca.pos(), ca.names(), ca.vals()).at(t.line, t.col);
                } else {
                    e = new Attribute(e, n.text).at(t.line, t.col);
                }
            } else if (o.isOp("(")) {
                next();
                CallArgs ca = callArgs();
                e = new Call(e, ca.pos(), ca.names(), ca.vals()).at(t.line, t.col);
            } else if (o.isOp("[")) {
                next();
                Expr idx = subscriptList();
                expectOp("]", "para fechar o indice");
                e = new Subscript(e, idx).at(t.line, t.col);
            } else {
                return e;
            }
        }
    }

    /** Argumentos de chamada ja analisados. */
    record CallArgs(Expr[] pos, String[] names, Expr[] vals) {
    }

    private CallArgs callArgs() {
        List<Expr> pos = new ArrayList<>();
        List<String> names = new ArrayList<>();
        List<Expr> vals = new ArrayList<>();
        while (!isOp(")")) {
            Token t = peek();
            if (t.isOp("*")) {
                next();
                pos.add(new Starred(expression()).at(t.line, t.col));
            } else if (t.isOp("**")) {
                next();
                names.add(null);
                vals.add(expression());
            } else if (t.type == T.NAME && peek(1).isOp("=") && !RESERVADAS.contains(t.text)) {
                next();
                next();
                names.add(t.text);
                vals.add(expression());
            } else {
                int refMark = cur.refs.size();
                int childMark = cur.children.size();
                Expr e = expression();
                if (isName("for")) {
                    e = compTail('l', null, e, refMark, childMark, t);
                    if (!isOp(")")) {
                        throw err(peek(), "a expressao geradora deve ser o unico argumento (coloque entre parenteses)");
                    }
                } else if (!names.isEmpty() && !(e instanceof Starred)) {
                    throw err(t, "argumento posicional depois de argumento nomeado");
                }
                pos.add(e);
            }
            if (!acceptOp(",")) {
                break;
            }
        }
        expectOp(")", "para fechar a chamada");
        return new CallArgs(pos.toArray(new Expr[0]), names.toArray(new String[0]), vals.toArray(new Expr[0]));
    }

    private Expr subscriptList() {
        Token t = peek();
        Expr first = sliceItem();
        if (!isOp(",")) {
            return first;
        }
        List<Expr> items = new ArrayList<>();
        items.add(first);
        while (acceptOp(",")) {
            if (isOp("]")) {
                break;
            }
            items.add(sliceItem());
        }
        return new SeqLit('t', items.toArray(new Expr[0])).at(t.line, t.col);
    }

    private Expr sliceItem() {
        Token t = peek();
        Expr lo = null;
        if (!isOp(":")) {
            lo = expression();
            if (!isOp(":")) {
                return lo;
            }
        }
        next();
        Expr hi = null;
        Expr step = null;
        if (!isOp(":") && !isOp("]") && !isOp(",")) {
            hi = expression();
        }
        if (acceptOp(":")) {
            if (!isOp("]") && !isOp(",")) {
                step = expression();
            }
        }
        return new SliceExpr(lo, hi, step).at(t.line, t.col);
    }

    // ---------------------------------------------------------------- atomos

    private Expr atom() {
        Token t = peek();
        switch (t.type) {
            case INT:
                next();
                if (t.value instanceof BigInteger) {
                    throw err(t, "inteiro literal fora do intervalo de 64 bits do AxonPL");
                }
                return new Const(t.value).at(t.line, t.col);
            case FLOAT:
                next();
                return new Const(t.value).at(t.line, t.col);
            case STR:
                return strings();
            case NAME:
                return nameAtom(t);
            case OP:
                if (t.text.equals("(")) {
                    return parenAtom();
                }
                if (t.text.equals("[")) {
                    return listAtom();
                }
                if (t.text.equals("{")) {
                    return braceAtom();
                }
                if (t.text.equals("...")) {
                    next();
                    return new Const(NoneType.NONE).at(t.line, t.col);
                }
                if (t.text.equals(":=")) {
                    throw unsupported(t, "o operador walrus (:=)");
                }
                throw err(t, "sintaxe invalida: '" + t.text + "' inesperado");
            case NEWLINE:
                throw err(t, "sintaxe invalida: a expressao esta incompleta");
            case EOF:
                throw err(t, "sintaxe invalida: fim inesperado do programa");
            default:
                throw err(t, "sintaxe invalida");
        }
    }

    private Expr nameAtom(Token t) {
        switch (t.text) {
            case "True":
                next();
                return new Const(Boolean.TRUE).at(t.line, t.col);
            case "False":
                next();
                return new Const(Boolean.FALSE).at(t.line, t.col);
            case "None":
                next();
                return new Const(NoneType.NONE).at(t.line, t.col);
            case "yield":
                throw unsupported(t, "yield (geradores)");
            case "await":
            case "async":
                throw unsupported(t, "async/await");
            case "lambda":
                return lambda();
            default:
                break;
        }
        if (RESERVADAS.contains(t.text)) {
            throw err(t, "a palavra reservada '" + t.text + "' nao pode ser usada aqui");
        }
        next();
        return newName(t.text, t);
    }

    private Expr strings() {
        Token first = peek();
        List<Token> parts = new ArrayList<>();
        while (peek().type == T.STR) {
            parts.add(next());
        }
        boolean anyF = false;
        boolean anyBytes = false;
        boolean anyStr = false;
        for (Token p : parts) {
            anyF |= p.fstr;
            anyBytes |= p.bytes;
            anyStr |= !p.bytes;
        }
        if (anyBytes && anyStr) {
            throw err(first, "nao e possivel misturar literais bytes e str");
        }
        if (anyBytes) {
            StringBuilder sb = new StringBuilder();
            for (Token p : parts) {
                sb.append(p.text);
            }
            return new Const(new PyBytes(sb.toString().getBytes(StandardCharsets.ISO_8859_1))).at(first.line, first.col);
        }
        if (!anyF) {
            StringBuilder sb = new StringBuilder();
            for (Token p : parts) {
                sb.append(p.text);
            }
            return new Const(sb.toString()).at(first.line, first.col);
        }
        List<Expr> out = new ArrayList<>();
        for (Token p : parts) {
            if (p.fstr) {
                FStrings.parse(p.text, p.raw, p, this, out);
            } else {
                out.add(new Const(p.text));
            }
        }
        return new FString(out.toArray(new Expr[0])).at(first.line, first.col);
    }

    private Expr parenAtom() {
        Token t = next();
        if (acceptOp(")")) {
            return new Const(PyTuple.VAZIA).at(t.line, t.col);
        }
        if (isName("yield")) {
            throw unsupported(peek(), "yield (geradores)");
        }
        int refMark = cur.refs.size();
        int childMark = cur.children.size();
        Expr first = starOrExpr();
        if (isName("for")) {
            Expr g = compTail('l', null, first, refMark, childMark, t);
            expectOp(")", "para fechar a expressao geradora");
            return g;
        }
        if (isOp(")")) {
            next();
            if (first instanceof Starred) {
                throw err(t, "desempacotamento * nao pode ser usado sozinho entre parenteses");
            }
            return first;
        }
        List<Expr> items = new ArrayList<>();
        items.add(first);
        while (acceptOp(",")) {
            if (isOp(")")) {
                break;
            }
            items.add(starOrExpr());
        }
        expectOp(")", "para fechar a tupla");
        return new SeqLit('t', items.toArray(new Expr[0])).at(t.line, t.col);
    }

    private Expr listAtom() {
        Token t = next();
        if (acceptOp("]")) {
            return new SeqLit('l', new Expr[0]).at(t.line, t.col);
        }
        int refMark = cur.refs.size();
        int childMark = cur.children.size();
        Expr first = starOrExpr();
        if (isName("for")) {
            Expr c = compTail('l', null, first, refMark, childMark, t);
            expectOp("]", "para fechar a compreensao de lista");
            return c;
        }
        List<Expr> items = new ArrayList<>();
        items.add(first);
        while (acceptOp(",")) {
            if (isOp("]")) {
                break;
            }
            items.add(starOrExpr());
        }
        expectOp("]", "para fechar a lista");
        return new SeqLit('l', items.toArray(new Expr[0])).at(t.line, t.col);
    }

    private Expr braceAtom() {
        Token t = next();
        if (acceptOp("}")) {
            return new DictLit(new Expr[0], new Expr[0]).at(t.line, t.col);
        }
        int refMark = cur.refs.size();
        int childMark = cur.children.size();
        if (isOp("**")) {
            return dictRest(t, new ArrayList<>(), new ArrayList<>());
        }
        Expr first = starOrExpr();
        if (isOp(":")) {
            next();
            Expr val = expression();
            if (isName("for")) {
                Expr c = compTail('d', first, val, refMark, childMark, t);
                expectOp("}", "para fechar a compreensao de dict");
                return c;
            }
            List<Expr> ks = new ArrayList<>();
            List<Expr> vs = new ArrayList<>();
            ks.add(first);
            vs.add(val);
            if (acceptOp(",")) {
                return dictRest(t, ks, vs);
            }
            expectOp("}", "para fechar o dict");
            return new DictLit(ks.toArray(new Expr[0]), vs.toArray(new Expr[0])).at(t.line, t.col);
        }
        if (isName("for")) {
            Expr c = compTail('s', null, first, refMark, childMark, t);
            expectOp("}", "para fechar a compreensao de set");
            return c;
        }
        List<Expr> items = new ArrayList<>();
        items.add(first);
        while (acceptOp(",")) {
            if (isOp("}")) {
                break;
            }
            items.add(starOrExpr());
        }
        expectOp("}", "para fechar o set");
        return new SeqLit('s', items.toArray(new Expr[0])).at(t.line, t.col);
    }

    private Expr dictRest(Token t, List<Expr> ks, List<Expr> vs) {
        while (!isOp("}")) {
            if (acceptOp("**")) {
                ks.add(null);
                vs.add(bitOr());
            } else {
                ks.add(expression());
                expectOp(":", "entre a chave e o valor do dict");
                vs.add(expression());
            }
            if (!acceptOp(",")) {
                break;
            }
        }
        expectOp("}", "para fechar o dict");
        return new DictLit(ks.toArray(new Expr[0]), vs.toArray(new Expr[0])).at(t.line, t.col);
    }

    // ---------------------------------------------------------------- compreensoes

    /**
     * Constroi a compreensao. Para dict, a e a chave e b o valor; nos demais, b e o elemento.
     */
    protected final Expr compTail(char kind, Expr keyExpr, Expr elt, int refMark, int childMark, Token at) {
        FunctionCode cc = newCode(kind == 'd' ? "<dictcomp>" : kind == 's' ? "<setcomp>" : "<listcomp>", at.line);
        Scope parent = cur;
        Scope cs = new Scope(parent, false, cc);
        // move nos criados antes de sabermos que era compreensao
        for (int k = refMark; k < parent.refs.size(); k++) {
            parent.refs.get(k).scope = cs;
            cs.refs.add(parent.refs.get(k));
        }
        while (parent.refs.size() > refMark) {
            parent.refs.remove(parent.refs.size() - 1);
        }
        List<Scope> moved = new ArrayList<>(parent.children.subList(childMark, parent.children.size() - 1));
        for (Scope m : moved) {
            m.parent = cs;
            cs.children.add(m);
            parent.children.remove(m);
        }
        cs.bound.add(".0");
        cs.bound.add(".r");
        List<Object[]> clauses = new ArrayList<>();
        Expr firstIter = null;
        boolean firstClause = true;
        while (isName("for")) {
            Token ft = next();
            cur = cs;
            Expr targetExpr = forTargets();
            Target target = toTarget(targetExpr);
            expectName("in", "na compreensao");
            Expr iter;
            if (firstClause) {
                cur = parent;
                firstIter = orTest();
                cur = cs;
                iter = newName(".0", ft);
                firstClause = false;
            } else {
                iter = orTest();
            }
            clauses.add(new Object[] {"for", target, iter, ft});
            while (isName("if")) {
                next();
                cur = cs;
                clauses.add(new Object[] {"if", orTest(), null, ft});
            }
            cur = parent;
        }
        cur = cs;
        Stmt body = new CompAppend(kind, keyExpr, elt);
        body.at(at.line, at.col);
        cur = parent;
        for (int k = clauses.size() - 1; k >= 0; k--) {
            Object[] c = clauses.get(k);
            Token ft = (Token) c[3];
            if (c[0].equals("for")) {
                body = new Flow.For((Target) c[1], (Expr) c[2], new Stmt[] {body}, new Stmt[0]).at(ft.line, ft.col);
            } else {
                body = new Flow.If((Expr) c[1], new Stmt[] {body}, new Stmt[0]).at(ft.line, ft.col);
            }
        }
        cc.body = new Stmt[] {body};
        return new Comp(cc, firstIter, kind).at(at.line, at.col);
    }

    // ---------------------------------------------------------------- lambda e alvos

    protected final Expr lambda() {
        Token t = next();
        Params ps = params(":", false);
        expectOp(":", "depois dos parametros do lambda");
        FunctionCode code = newCode("<lambda>", t.line);
        Scope parent = cur;
        Scope s = new Scope(parent, false, code);
        ps.install(code, s, this);
        cur = s;
        code.lambdaBody = expression();
        cur = parent;
        return new MakeFunction(code).at(t.line, t.col);
    }

    /** Parametros analisados (ainda sem escopo). */
    protected static final class Params {
        final List<String> pos = new ArrayList<>();
        final List<Expr> posDefaults = new ArrayList<>();
        final List<String> kwonly = new ArrayList<>();
        final List<Expr> kwDefaults = new ArrayList<>();
        String varargs;
        String varkw;
        final List<String> annotations = new ArrayList<>();
        final List<String> defaultTexts = new ArrayList<>();
        final List<String> kinds = new ArrayList<>();
        final List<String> order = new ArrayList<>();

        void install(FunctionCode code, Scope s, ExprParser p) {
            for (String n : pos) {
                s.bound.add(n);
            }
            for (String n : kwonly) {
                s.bound.add(n);
            }
            if (varargs != null) {
                s.bound.add(varargs);
            }
            if (varkw != null) {
                s.bound.add(varkw);
            }
            code.nPos = pos.size();
            code.posNames = pos.toArray(new String[0]);
            code.defaults = posDefaults.toArray(new Expr[0]);
            code.kwonlyNames = kwonly.toArray(new String[0]);
            code.kwDefaults = kwDefaults.toArray(new Expr[0]);
            code.varargs = varargs;
            code.varkw = varkw;
            code.simple = kwonly.isEmpty() && varargs == null && varkw == null;
        }
    }

    protected final Params params(String end, boolean annotations) {
        Params ps = new Params();
        boolean starSeen = false;
        while (!isOp(end)) {
            Token t = peek();
            if (t.isOp("/")) {
                next();
            } else if (t.isOp("**")) {
                next();
                ps.varkw = ident("depois de '**'");
                ps.order.add(ps.varkw);
                ps.kinds.add("kwargs");
                ps.annotations.add(annotations ? annotation() : null);
                ps.defaultTexts.add(null);
            } else if (t.isOp("*")) {
                next();
                starSeen = true;
                if (peek().type == T.NAME && !RESERVADAS.contains(peek().text)) {
                    ps.varargs = ident("depois de '*'");
                    ps.order.add(ps.varargs);
                    ps.kinds.add("args");
                    ps.annotations.add(annotations ? annotation() : null);
                    ps.defaultTexts.add(null);
                }
            } else {
                String n = ident("como parametro");
                String ann = annotations ? annotation() : null;
                Expr def = null;
                String defText = null;
                if (acceptOp("=")) {
                    int st = i;
                    def = expression();
                    defText = textFrom(st);
                }
                ps.order.add(n);
                ps.annotations.add(ann);
                ps.defaultTexts.add(defText);
                if (starSeen) {
                    ps.kwonly.add(n);
                    ps.kwDefaults.add(def);
                    ps.kinds.add("kwonly");
                } else {
                    ps.pos.add(n);
                    ps.kinds.add("pos");
                    if (def != null) {
                        ps.posDefaults.add(def);
                    } else if (!ps.posDefaults.isEmpty()) {
                        throw err(t, "parametro sem valor padrao depois de parametro com valor padrao");
                    }
                }
            }
            if (!acceptOp(",")) {
                break;
            }
        }
        return ps;
    }

    private String annotation() {
        if (!acceptOp(":")) {
            return null;
        }
        int refMark = cur.refs.size();
        int childMark = cur.children.size();
        int st = i;
        expression();
        String txt = textFrom(st);
        while (cur.refs.size() > refMark) {
            cur.refs.remove(cur.refs.size() - 1);
        }
        while (cur.children.size() > childMark) {
            cur.children.remove(cur.children.size() - 1);
        }
        return txt;
    }

    /** Converte uma expressao em alvo de atribuicao, ligando os nomes no escopo atual. */
    protected final Target toTarget(Expr e) {
        if (e instanceof Name n) {
            bind(n.id);
            return n;
        }
        if (e instanceof com.axonbase.pl.ast.Attribute a) {
            return a;
        }
        if (e instanceof Subscript s) {
            return s;
        }
        if (e instanceof SeqLit sl && (sl.kind == 't' || sl.kind == 'l')) {
            Target[] items = new Target[sl.items.length];
            int star = -1;
            for (int k = 0; k < items.length; k++) {
                Expr it = sl.items[k];
                if (it instanceof Starred st) {
                    if (star >= 0) {
                        throw new PlSyntaxError(e.line, e.col, "so e permitido um alvo com * na atribuicao", "");
                    }
                    star = k;
                    items[k] = toTarget(st.e);
                } else {
                    items[k] = toTarget(it);
                }
            }
            return new TupleTarget(items, star);
        }
        Token t = toks.get(Math.max(0, i - 1));
        throw Lexer.erro(src, e.line > 0 ? e.line : t.line, e.col > 0 ? e.col : t.col,
                "nao e possivel atribuir a esta expressao");
    }
}
