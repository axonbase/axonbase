package com.axonbase.core.engine;

import com.axonbase.parser.token.Lexer;
import com.axonbase.parser.token.TokenType;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Os nomes que podem seguir por um DATABASE LINK, com a credencial dele: o outro servidor pode ser de uma versão que
 * não cita nomes ao gravar o catálogo.
 */
final class LinkGuard {

    private static final Pattern PLAIN = Pattern.compile("[\\p{L}_][\\p{L}\\p{Nd}_]*");

    private LinkGuard() {
    }

    /** Só letras, números e {@code _}, sem começar por número. */
    static boolean plain(String name) {
        return PLAIN.matcher(name).matches();
    }

    /**
     * O primeiro nome do comando que não é simples. Vale para qualquer posição (JOIN, aresta, subconsulta, campo): só
     * o que foi escrito entre crases traz literal no token.
     */
    static Optional<String> refusedQuotedName(String axonql) {
        for (var token : Lexer.tokenize(axonql)) {
            if (token.is(TokenType.IDENT) && token.literal() instanceof String quoted && !plain(quoted)) {
                return Optional.of(quoted);
            }
        }
        return Optional.empty();
    }
}
