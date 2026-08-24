package com.axonbase.parser.token;

/** Token dunha AxonQL. O campo {@code literal} garda o valor semántico cando procede. */
public record Token(TokenType type, String text, int start, int end, Object literal) {

    public boolean is(TokenType t) {
        return type == t;
    }

    public boolean isKeyword(String kw) {
        return type == TokenType.KEYWORD && text.equalsIgnoreCase(kw);
    }

    @Override
    public String toString() {
        return type + "(" + text + ")";
    }
}