package com.axonbase.pl.lex;

/** Token produzido pelo analisador lexico do AxonPL. */
public final class Token {

    /** Categoria do token. */
    public enum T { NAME, INT, FLOAT, STR, NEWLINE, INDENT, DEDENT, OP, EOF }

    public final T type;
    /** Texto do nome/operador; corpo da string (cru para f-strings). */
    public final String text;
    /** Valor numerico (Long, BigInteger so para 2^63, ou Double). */
    public final Object value;
    public final int line;
    public final int col;
    public final int pos;
    public final int end;
    public final boolean raw;
    public final boolean fstr;
    public final boolean bytes;

    public Token(T type, String text, Object value, int line, int col, int pos, int end,
                 boolean raw, boolean fstr, boolean bytes) {
        this.type = type;
        this.text = text;
        this.value = value;
        this.line = line;
        this.col = col;
        this.pos = pos;
        this.end = end;
        this.raw = raw;
        this.fstr = fstr;
        this.bytes = bytes;
    }

    /** Verdadeiro se for operador com o texto dado. */
    public boolean isOp(String s) {
        return type == T.OP && text.equals(s);
    }

    /** Verdadeiro se for nome (palavra) com o texto dado. */
    public boolean isName(String s) {
        return type == T.NAME && text.equals(s);
    }

    @Override
    public String toString() {
        return type + "(" + text + ")@" + line + ":" + col;
    }
}
