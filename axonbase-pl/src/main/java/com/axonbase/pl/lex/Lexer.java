package com.axonbase.pl.lex;

import com.axonbase.pl.PlSyntaxError;
import com.axonbase.pl.lex.Token.T;
import java.math.BigInteger;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Analisador lexico do AxonPL: indentacao por espacos, strings, numeros e operadores. */
public final class Lexer {

    private final String s;
    private final int n;
    private int pos;
    private int line = 1;
    private int lineStart;
    private int depth;
    private final List<Token> out = new ArrayList<>();
    private final ArrayDeque<Integer> indents = new ArrayDeque<>();

    /** Normaliza quebras de linha (o texto original do programa e guardado normalizado). */
    public static String normalizar(String src) {
        return src.replace("\r\n", "\n").replace('\r', '\n');
    }

    public Lexer(String normalizedSource) {
        this.s = normalizedSource;
        this.n = s.length();
    }

    public PlSyntaxError err(int ln, int col, String msg) {
        return erro(s, ln, col, msg);
    }

    /** Cria um erro de sintaxe com o trecho da linha. */
    public static PlSyntaxError erro(String src, int ln, int col, String msg) {
        String[] ls = src.split("\n", -1);
        String trecho = ln >= 1 && ln <= ls.length ? ls[ln - 1] : "";
        return new PlSyntaxError(ln, col, msg, trecho);
    }

    private int col() {
        return pos - lineStart + 1;
    }

    private void add(T t, String text, Object v, int ln, int col, int start, boolean raw, boolean f, boolean b) {
        out.add(new Token(t, text, v, ln, col, start, pos, raw, f, b));
    }

    /** Executa a analise. */
    public List<Token> tokenize() {
        indents.push(0);
        boolean atStart = true;
        while (pos < n) {
            if (atStart && depth == 0) {
                int p = pos;
                int cols = 0;
                boolean tab = false;
                while (p < n && (s.charAt(p) == ' ' || s.charAt(p) == '\t' || s.charAt(p) == '\f')) {
                    if (s.charAt(p) == '\t') {
                        tab = true;
                    }
                    cols++;
                    p++;
                }
                if (p >= n) {
                    pos = p;
                    break;
                }
                char c = s.charAt(p);
                if (c == '\n') {
                    pos = p + 1;
                    line++;
                    lineStart = pos;
                    continue;
                }
                if (c == '#') {
                    while (p < n && s.charAt(p) != '\n') {
                        p++;
                    }
                    pos = p;
                    continue;
                }
                if (tab) {
                    throw err(line, 1, "tabulacao na indentacao nao e permitida no AxonPL: use apenas espacos");
                }
                int top = indents.peek();
                pos = p;
                if (cols > top) {
                    indents.push(cols);
                    add(T.INDENT, "", null, line, 1, pos, false, false, false);
                } else if (cols < top) {
                    while (cols < indents.peek()) {
                        indents.pop();
                        add(T.DEDENT, "", null, line, 1, pos, false, false, false);
                    }
                    if (cols != indents.peek()) {
                        throw err(line, 1, "indentacao inconsistente: o recuo nao coincide com nenhum nivel anterior");
                    }
                }
                atStart = false;
            }
            char c = s.charAt(pos);
            if (c == ' ' || c == '\t' || c == '\f') {
                pos++;
                continue;
            }
            if (c == '\n') {
                int st = pos;
                int cl = col();
                pos++;
                if (depth > 0) {
                    line++;
                    lineStart = pos;
                    continue;
                }
                if (!out.isEmpty() && out.get(out.size() - 1).type != T.NEWLINE) {
                    out.add(new Token(T.NEWLINE, "", null, line, cl, st, pos, false, false, false));
                }
                line++;
                lineStart = pos;
                atStart = true;
                continue;
            }
            if (c == '#') {
                while (pos < n && s.charAt(pos) != '\n') {
                    pos++;
                }
                continue;
            }
            if (c == '\\') {
                if (pos + 1 < n && s.charAt(pos + 1) == '\n') {
                    pos += 2;
                    line++;
                    lineStart = pos;
                    continue;
                }
                throw err(line, col(), "barra invertida inesperada (so pode continuar a linha no fim dela)");
            }
            if (Character.isDigit(c) || (c == '.' && pos + 1 < n && Character.isDigit(s.charAt(pos + 1)))) {
                number();
                continue;
            }
            if (Character.isLetter(c) || c == '_') {
                name();
                continue;
            }
            if (c == '"' || c == '\'') {
                string(pos, false, false, false);
                continue;
            }
            operator();
        }
        if (depth > 0) {
            throw err(line, col(), "parenteses, colchetes ou chaves nao fechados ate o fim do programa");
        }
        if (!out.isEmpty() && out.get(out.size() - 1).type != T.NEWLINE) {
            out.add(new Token(T.NEWLINE, "", null, line, col(), pos, pos, false, false, false));
        }
        while (indents.peek() > 0) {
            indents.pop();
            out.add(new Token(T.DEDENT, "", null, line, 1, pos, pos, false, false, false));
        }
        out.add(new Token(T.EOF, "", null, line, col(), pos, pos, false, false, false));
        return out;
    }

    private void name() {
        int st = pos;
        int cl = col();
        while (pos < n && (Character.isLetterOrDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
            pos++;
        }
        String w = s.substring(st, pos);
        if (pos < n && (s.charAt(pos) == '"' || s.charAt(pos) == '\'') && w.length() <= 2) {
            String lw = w.toLowerCase();
            boolean ok = switch (lw) {
                case "r", "f", "b", "u", "rb", "br", "fr", "rf" -> true;
                default -> false;
            };
            if (ok) {
                string(st, lw.contains("r"), lw.contains("f"), lw.contains("b"));
                return;
            }
        }
        add(T.NAME, w, null, line, cl, st, false, false, false);
    }

    private void number() {
        int st = pos;
        int cl = col();
        char c = s.charAt(pos);
        if (c == '0' && pos + 1 < n && "xXbBoO".indexOf(s.charAt(pos + 1)) >= 0) {
            char k = Character.toLowerCase(s.charAt(pos + 1));
            int radix = k == 'x' ? 16 : k == 'b' ? 2 : 8;
            pos += 2;
            int ds = pos;
            while (pos < n && (Character.digit(s.charAt(pos), radix) >= 0 || s.charAt(pos) == '_')) {
                pos++;
            }
            String digits = s.substring(ds, pos).replace("_", "");
            if (digits.isEmpty()) {
                throw err(line, cl, "literal numerico invalido");
            }
            addInt(new BigInteger(digits, radix), st, cl);
            return;
        }
        boolean isFloat = false;
        while (pos < n && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
            pos++;
        }
        if (pos < n && s.charAt(pos) == '.') {
            isFloat = true;
            pos++;
            while (pos < n && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                pos++;
            }
        }
        if (pos < n && (s.charAt(pos) == 'e' || s.charAt(pos) == 'E')) {
            int save = pos;
            pos++;
            if (pos < n && (s.charAt(pos) == '+' || s.charAt(pos) == '-')) {
                pos++;
            }
            if (pos < n && Character.isDigit(s.charAt(pos))) {
                isFloat = true;
                while (pos < n && (Character.isDigit(s.charAt(pos)) || s.charAt(pos) == '_')) {
                    pos++;
                }
            } else {
                pos = save;
            }
        }
        if (pos < n && (s.charAt(pos) == 'j' || s.charAt(pos) == 'J')) {
            throw err(line, cl, "numeros complexos nao sao suportados no AxonPL 1.0");
        }
        if (pos < n && (Character.isLetter(s.charAt(pos)) || s.charAt(pos) == '_')) {
            throw err(line, cl, "literal numerico invalido");
        }
        String txt = s.substring(st, pos).replace("_", "");
        if (isFloat) {
            add(T.FLOAT, txt, Double.parseDouble(txt), line, cl, st, false, false, false);
        } else {
            addInt(new BigInteger(txt), st, cl);
        }
    }

    private void addInt(BigInteger v, int st, int cl) {
        Object val;
        if (v.bitLength() <= 63) {
            val = v.longValue();
        } else if (v.equals(BigInteger.ONE.shiftLeft(63))) {
            val = v;
        } else {
            throw err(line, cl, "inteiro literal fora do intervalo de 64 bits do AxonPL (use decimal(\"...\") para numeros grandes)");
        }
        add(T.INT, s.substring(st, pos), val, line, cl, st, false, false, false);
    }

    private void string(int tokStart, boolean raw, boolean f, boolean bytes) {
        int cl = tokStart - lineStart + 1;
        int startLine = line;
        while (s.charAt(pos) != '"' && s.charAt(pos) != '\'') {
            pos++;
        }
        char q = s.charAt(pos);
        boolean triple = pos + 2 < n && s.charAt(pos + 1) == q && s.charAt(pos + 2) == q;
        pos += triple ? 3 : 1;
        StringBuilder body = new StringBuilder();
        boolean closed = false;
        while (pos < n) {
            char c = s.charAt(pos);
            if (c == '\\' && pos + 1 < n) {
                body.append(c).append(s.charAt(pos + 1));
                if (s.charAt(pos + 1) == '\n') {
                    line++;
                    lineStart = pos + 2;
                }
                pos += 2;
                continue;
            }
            if (c == q) {
                if (!triple) {
                    pos++;
                    closed = true;
                    break;
                }
                if (pos + 2 < n + 0 && s.startsWith("" + q + q + q, pos)) {
                    pos += 3;
                    closed = true;
                    break;
                }
            } else if (c == '\n') {
                if (!triple) {
                    throw err(startLine, cl, "string nao terminada (faltou fechar as aspas)");
                }
                line++;
                lineStart = pos + 1;
            }
            body.append(c);
            pos++;
        }
        if (!closed) {
            throw err(startLine, cl, "string nao terminada (faltou fechar as aspas)");
        }
        String text = body.toString();
        if (!raw && !f) {
            text = Escapes.cook(text, bytes, msg -> err(startLine, cl, msg));
        }
        if (bytes) {
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) > 255) {
                    throw err(startLine, cl, "bytes so aceitam caracteres ASCII/latin-1 no literal");
                }
            }
        }
        out.add(new Token(T.STR, text, null, startLine, cl, tokStart, pos, raw, f, bytes));
    }

    private static final String[] OPS3 = {"**=", "//=", ">>=", "<<=", "..."};
    private static final String[] OPS2 = {"**", "//", "==", "!=", "<=", ">=", "+=", "-=", "*=", "/=", "%=",
        "&=", "|=", "^=", "->", "<<", ">>", ":=", "@="};

    private void operator() {
        int st = pos;
        int cl = col();
        for (String o : OPS3) {
            if (s.startsWith(o, pos)) {
                pos += 3;
                add(T.OP, o, null, line, cl, st, false, false, false);
                return;
            }
        }
        for (String o : OPS2) {
            if (s.startsWith(o, pos)) {
                pos += 2;
                add(T.OP, o, null, line, cl, st, false, false, false);
                return;
            }
        }
        char c = s.charAt(pos);
        if ("+-*/%&|^~<>()[]{},:.;=@".indexOf(c) < 0) {
            throw err(line, cl, "caractere invalido '" + c + "'");
        }
        if ("([{".indexOf(c) >= 0) {
            depth++;
        } else if (")]}".indexOf(c) >= 0) {
            depth--;
            if (depth < 0) {
                throw err(line, cl, "'" + c + "' sem abertura correspondente");
            }
        }
        pos++;
        add(T.OP, String.valueOf(c), null, line, cl, st, false, false, false);
    }
}
