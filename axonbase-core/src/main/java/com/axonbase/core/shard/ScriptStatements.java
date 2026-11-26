package com.axonbase.core.shard;

import com.axonbase.parser.AxonQl;
import com.axonbase.parser.ast.Statement;
import com.axonbase.parser.token.Lexer;
import com.axonbase.parser.token.Token;
import com.axonbase.parser.token.TokenType;

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
        for (Token token : Lexer.tokenize(script)) {
            TokenType type = token.type();
            if (type == TokenType.EOF) {
                break;
            }
            if (type == TokenType.SEMI && depth == 0) {
                if (start >= 0) {
                    texts.add(script.substring(start, end));
                    start = -1;
                }
                continue;
            }
            if (type == TokenType.LPAREN || type == TokenType.LBRACE || type == TokenType.LBRACKET) {
                depth++;
            } else if (type == TokenType.RPAREN || type == TokenType.RBRACE || type == TokenType.RBRACKET) {
                depth--;
            }
            if (start < 0) {
                start = token.start();
            }
            end = token.end();
        }
        if (start >= 0) {
            texts.add(script.substring(start, end));
        }
        return texts;
    }
}
