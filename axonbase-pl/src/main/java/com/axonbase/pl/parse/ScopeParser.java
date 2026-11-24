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

/** Construcoes que abrem escopo: compreensoes, lambda, parametros e alvos de atribuicao. */
abstract class ScopeParser extends ParserBase {

    protected ScopeParser(String src, List<Token> toks) {
        super(src, toks);
    }

    protected abstract Expr expression();

    protected abstract Expr orTest();

    protected abstract Expr forTargets();

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

        void install(FunctionCode code, Scope s, ParserBase p) {
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
