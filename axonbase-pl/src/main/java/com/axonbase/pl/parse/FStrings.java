package com.axonbase.pl.parse;

import com.axonbase.pl.PlSyntaxError;
import com.axonbase.pl.ast.Const;
import com.axonbase.pl.ast.Expr;
import com.axonbase.pl.ast.FPart;
import com.axonbase.pl.ast.FString;
import com.axonbase.pl.lex.Escapes;
import com.axonbase.pl.lex.Token;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Analise de f-strings: separa literais e campos {expr!conv:spec}. */
final class FStrings {
    private FStrings() {
    }

    /** Interface para analisar o texto de uma expressao. */
    interface ExprSource {
        Expr parseExpr(String text, Token at);

        PlSyntaxError error(Token at, String msg);
    }

    static void parse(String body, boolean raw, Token tok, ExprSource es, List<Expr> out) {
        StringBuilder lit = new StringBuilder();
        int n = body.length();
        int i = 0;
        Function<String, PlSyntaxError> errFn = m -> es.error(tok, m);
        while (i < n) {
            char c = body.charAt(i);
            if (c == '{') {
                if (i + 1 < n && body.charAt(i + 1) == '{') {
                    lit.append('{');
                    i += 2;
                    continue;
                }
                flush(lit, raw, out, errFn);
                i = field(body, i + 1, raw, tok, es, out);
            } else if (c == '}') {
                if (i + 1 < n && body.charAt(i + 1) == '}') {
                    lit.append('}');
                    i += 2;
                    continue;
                }
                throw es.error(tok, "'}' solto na f-string (use '}}' para um '}' literal)");
            } else {
                if (c == '\\' && i + 1 < n && !raw) {
                    lit.append(c).append(body.charAt(i + 1));
                    i += 2;
                    continue;
                }
                lit.append(c);
                i++;
            }
        }
        flush(lit, raw, out, errFn);
    }

    private static void flush(StringBuilder lit, boolean raw, List<Expr> out, Function<String, PlSyntaxError> errFn) {
        if (lit.length() > 0) {
            String s = lit.toString();
            out.add(new Const(raw ? s : Escapes.cook(s, false, errFn)));
            lit.setLength(0);
        }
    }

    private static int field(String body, int start, boolean raw, Token tok, ExprSource es, List<Expr> out) {
        int n = body.length();
        int depth = 0;
        int j = start;
        char quote = 0;
        int exprEnd = -1;
        while (j < n) {
            char c = body.charAt(j);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                j++;
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if (c == ')' || c == ']') {
                depth--;
            } else if (c == '}') {
                if (depth == 0) {
                    exprEnd = j;
                    break;
                }
                depth--;
            } else if (depth == 0 && (c == ':' || (c == '!' && j + 1 < n && body.charAt(j + 1) != '='))) {
                exprEnd = j;
                break;
            }
            j++;
        }
        if (exprEnd < 0) {
            throw es.error(tok, "f-string com '{' sem fechamento");
        }
        String text = body.substring(start, exprEnd);
        boolean debug = false;
        String trimmed = text.stripTrailing();
        if (trimmed.endsWith("=") && !trimmed.endsWith("==") && !trimmed.endsWith("!=") && !trimmed.endsWith("<=")
                && !trimmed.endsWith(">=")) {
            debug = true;
            text = trimmed.substring(0, trimmed.length() - 1);
            out.add(new Const(trimmed));
        }
        if (text.isBlank()) {
            throw es.error(tok, "expressao vazia dentro de '{}' na f-string");
        }
        Expr e = es.parseExpr(text, tok);
        char conv = 0;
        Expr spec = null;
        j = exprEnd;
        if (j < n && body.charAt(j) == '!') {
            if (j + 1 >= n) {
                throw es.error(tok, "f-string incompleta depois de '!'");
            }
            conv = body.charAt(j + 1);
            if (conv != 'r' && conv != 's' && conv != 'a') {
                throw es.error(tok, "conversao invalida na f-string: use !r, !s ou !a");
            }
            j += 2;
        }
        if (j < n && body.charAt(j) == ':') {
            int k = j + 1;
            int d = 0;
            while (k < n) {
                char c = body.charAt(k);
                if (c == '{') {
                    d++;
                } else if (c == '}') {
                    if (d == 0) {
                        break;
                    }
                    d--;
                }
                k++;
            }
            if (k >= n) {
                throw es.error(tok, "f-string com '{' sem fechamento");
            }
            List<Expr> sp = new ArrayList<>();
            parse(body.substring(j + 1, k), true, tok, es, sp);
            spec = new FString(sp.toArray(new Expr[0]));
            j = k;
        }
        if (j >= n || body.charAt(j) != '}') {
            throw es.error(tok, "f-string com '{' sem fechamento");
        }
        if (debug && conv == 0 && spec == null) {
            conv = 'r';
        }
        out.add(new FPart(e, conv, spec));
        return j + 1;
    }
}
