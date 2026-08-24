package com.axonbase.parser.token;

/** Tipos de token da AxonQL. */
public enum TokenType {
    EOF,
    IDENT,
    KEYWORD,
    INT,
    FLOAT,
    DECIMAL,
    STRING,
    DATETIME,
    UUID,
    BYTES,
    DURATION,
    PARAM,
    RECORD_ID_STR,

    LPAREN,
    RPAREN,
    LBRACE,
    RBRACE,
    LBRACKET,
    RBRACKET,
    SEMI,
    COMMA,
    DOT,

    COLON,
    COLON_COLON,
    ASSIGN,

    EQ,
    EQ_EQ,
    NE,
    LT,
    GT,
    LE,
    GE,

    PLUS,
    MINUS,
    STAR,
    STAR_STAR,
    SLASH,
    PERCENT,
    BANG,
    QMARK,

    AND_AND,
    OR_OR,
    QUESTION_QUESTION,
    QMARK_COLON,
    QMARK_EQ,
    STAR_EQ,

    DOT_DOT,
    DOT_DOT_EQ,
    GT_DOT_DOT,
    GT_DOT_DOT_EQ,

    ARROW,
    LARROW,
    B_ARROW,
    AT,
    MATCH
}
