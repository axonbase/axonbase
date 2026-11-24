package com.axonbase.pl.parse;

import com.axonbase.pl.PlSyntaxError;
import com.axonbase.pl.ast.FunctionCode;
import com.axonbase.pl.ast.Name;
import com.axonbase.pl.ast.Scope;
import com.axonbase.pl.lex.Lexer;
import com.axonbase.pl.lex.Token;
import com.axonbase.pl.lex.Token.T;
import java.util.List;
import java.util.Set;

/** Cursor de tokens, erros e escopo atual do analisador sintatico. */
abstract class ParserBase {
    static final Set<String> RESERVADAS = Set.of("False", "None", "True", "and", "as", "assert", "async", "await",
            "break", "class", "continue", "def", "del", "elif", "else", "except", "finally", "for", "from", "global",
            "if", "import", "in", "is", "lambda", "nonlocal", "not", "or", "pass", "raise", "return", "try", "while",
            "with", "yield");

    protected final String src;
    protected final List<Token> toks;
    protected int i;
    protected Scope cur;
    protected Scope module;

    protected ParserBase(String src, List<Token> toks) {
        this.src = src;
        this.toks = toks;
    }

    protected final Token peek() {
        return toks.get(i);
    }

    protected final Token peek(int k) {
        return toks.get(Math.min(i + k, toks.size() - 1));
    }

    protected final Token next() {
        Token t = toks.get(i);
        if (t.type != T.EOF) {
            i++;
        }
        return t;
    }

    protected final boolean isOp(String s) {
        return peek().isOp(s);
    }

    protected final boolean isName(String s) {
        return peek().isName(s);
    }

    protected final boolean acceptOp(String s) {
        if (peek().isOp(s)) {
            i++;
            return true;
        }
        return false;
    }

    protected final boolean acceptName(String s) {
        if (peek().isName(s)) {
            i++;
            return true;
        }
        return false;
    }

    protected final Token expectOp(String s, String ctx) {
        if (!peek().isOp(s)) {
            throw err(peek(), "esperado '" + s + "'" + (ctx.isEmpty() ? "" : " " + ctx));
        }
        return next();
    }

    protected final Token expectName(String s, String ctx) {
        if (!peek().isName(s)) {
            throw err(peek(), "esperado '" + s + "'" + (ctx.isEmpty() ? "" : " " + ctx));
        }
        return next();
    }

    protected final PlSyntaxError err(Token t, String msg) {
        return Lexer.erro(src, t.line, t.col, msg);
    }

    protected final PlSyntaxError unsupported(Token t, String what) {
        return err(t, what + " nao e suportado no AxonPL 1.0");
    }

    protected final String ident(String ctx) {
        Token t = peek();
        if (t.type != T.NAME || RESERVADAS.contains(t.text)) {
            throw err(t, "esperado um nome (identificador)" + (ctx.isEmpty() ? "" : " " + ctx));
        }
        i++;
        return t.text;
    }

    protected final Name newName(String id, Token t) {
        Name n = new Name(id, cur);
        n.line = t.line;
        n.col = t.col;
        return n;
    }

    /** Marca o nome como ligado no escopo atual (atribuicao, parametro, import, ...). */
    protected final void bind(String id) {
        if (cur.module || (!cur.globals.contains(id) && !cur.nonlocals.contains(id))) {
            cur.bound.add(id);
        }
    }

    protected final String textFrom(int fromTokIdx) {
        Token a = toks.get(fromTokIdx);
        Token b = toks.get(Math.max(i - 1, fromTokIdx));
        return src.substring(a.pos, b.end);
    }

    protected static FunctionCode newCode(String name, int line) {
        FunctionCode c = new FunctionCode();
        c.name = name;
        c.line = line;
        return c;
    }
}
