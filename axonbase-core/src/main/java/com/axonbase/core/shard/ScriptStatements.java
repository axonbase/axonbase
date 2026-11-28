package com.axonbase.core.shard;

import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Statement;

import java.util.ArrayList;
import java.util.List;

/**
 * Separa um script nas instruções, guardando o texto original de cada uma: o gateway envia ao shard exatamente o que o
 * cliente escreveu. Cada trecho é conferido: precisa analisar sozinho para a mesma árvore que teve dentro do script.
 */
final class ScriptStatements {

    /**
     * @param statement árvore da instrução
     * @param text      trecho original dela, sem o {@code ;}
     */
    record Piece(Statement statement, String text) {
    }

    private ScriptStatements() {
    }

    static List<Piece> split(String script) {
        List<Statement> statements = AxonQl.parse(script).statements();
        List<String> texts = texts(script);
        if (texts.size() != statements.size()) {
            // um bloco que a separação não reconhece: vale a instrução única, quando é o caso
            if (statements.size() == 1) {
                return List.of(new Piece(statements.get(0), script));
            }
            throw ShardException.refused("script_unsplittable");
        }
        List<Piece> pieces = new ArrayList<>(statements.size());
        for (int i = 0; i < statements.size(); i++) {
            List<Statement> alone = AxonQl.parse(texts.get(i)).statements();
            if (alone.size() != 1 || !alone.get(0).equals(statements.get(i))) {
                throw ShardException.refused("script_unsplittable");
            }
            pieces.add(new Piece(statements.get(i), texts.get(i)));
        }
        return pieces;
    }

    /** Os trechos entre os {@code ;} que não estão dentro de parênteses, chaves ou colchetes. */
    private static List<String> texts(String script) {
        List<String> texts = new ArrayList<>();
        int depth = 0;
        int start = -1;
        int end = -1;
        for (Unit unit : units(script)) {
            char first = script.charAt(unit.start());
            if (first == ';' && depth == 0) {
                if (start >= 0) {
                    texts.add(script.substring(start, end));
                    start = -1;
                }
                continue;
            }
            if (first == '(' || first == '{' || first == '[') {
                depth++;
            } else if (first == ')' || first == '}' || first == ']') {
                depth--;
            }
            if (start < 0) {
                start = unit.start();
            }
            end = unit.end();
        }
        if (start >= 0) {
            texts.add(script.substring(start, end));
        }
        return texts;
    }

    /** Um pedaço do texto: uma palavra, um texto entre delimitadores ou um símbolo, sem espaços nem comentários. */
    record Unit(int start, int end) {
    }

    /**
     * Percorre o script pelas regras do analisador léxico para textos, nomes entre crases, blocos entre cifrões e
     * comentários. Os deslocamentos dos tokens do analisador não servem para recortar o texto.
     */
    static List<Unit> units(String script) {
        List<Unit> units = new ArrayList<>();
        int length = script.length();
        int i = 0;
        while (i < length) {
            char c = script.charAt(i);
            char next = i + 1 < length ? script.charAt(i + 1) : 0;
            if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
                i++;
                continue;
            }
            if (c == '-' && next == '-' || c == '/' && next == '/') {
                int line = script.indexOf('\n', i);
                i = line < 0 ? length : line;
                continue;
            }
            if (c == '/' && next == '*') {
                int close = script.indexOf("*/", i + 2);
                i = close < 0 ? length : close + 2;
                continue;
            }
            int from = i;
            if (c == '\'' || c == '"') {
                i = afterString(script, i);
            } else if (c == '`' || c == '\u27e8') {
                int close = script.indexOf(c == '`' ? '`' : '\u27e9', i + 1);
                i = close < 0 ? length : close + 1;
            } else if (c == '$') {
                i = afterDollar(script, i);
            } else if (isWord(c)) {
                while (i < length && isWord(script.charAt(i))) {
                    i++;
                }
            } else if (c == '<' && next == '-') {
                // a seta "<-" consome o traço, que não abre comentário
                i += 2;
            } else {
                i++;
            }
            units.add(new Unit(from, i));
        }
        return units;
    }

    private static boolean isWord(char c) {
        return Character.isAlphabetic(c) || Character.isDigit(c) || c == '_';
    }

    private static int afterString(String script, int open) {
        char quote = script.charAt(open);
        int i = open + 1;
        while (i < script.length()) {
            char c = script.charAt(i);
            if (c == '\\') {
                i += 2;
                continue;
            }
            i++;
            if (c == quote) {
                return i;
            }
        }
        return script.length();
    }

    /** Depois de um {@code $nome} ou de um bloco {@code $$ ... $$} (ou {@code $tag$ ... $tag$}). */
    private static int afterDollar(String script, int open) {
        int i = open + 1;
        while (i < script.length() && (Character.isLetterOrDigit(script.charAt(i)) || script.charAt(i) == '_')) {
            i++;
        }
        boolean delimiter = i < script.length() && script.charAt(i) == '$'
            && !(i > open + 1 && Character.isDigit(script.charAt(open + 1)));
        if (!delimiter) {
            return i;
        }
        String mark = script.substring(open, i + 1);
        int close = script.indexOf(mark, i + 1);
        return close < 0 ? script.length() : close + mark.length();
    }
}
