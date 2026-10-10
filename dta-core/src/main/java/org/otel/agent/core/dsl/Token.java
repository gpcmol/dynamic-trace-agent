package org.otel.agent.core.dsl;

/**
 * A lexical token of the condition language.
 *
 * @param type what kind of token
 * @param text the source text (string literals without quotes)
 * @param position 0-based character position in the expression, for error messages
 */
record Token(Type type, String text, int position) {

  enum Type {
    LEFT_PAREN,
    RIGHT_PAREN,
    LEFT_BRACKET,
    RIGHT_BRACKET,
    COMMA,
    PLUS,
    MINUS,
    STAR,
    SLASH,
    PERCENT,
    EQUAL,
    NOT_EQUAL,
    LESS,
    LESS_OR_EQUAL,
    GREATER,
    GREATER_OR_EQUAL,
    AND,
    OR,
    NOT,
    IN,
    TRUE,
    FALSE,
    NULL,
    STRING,
    NUMBER,
    PATH,
    IDENTIFIER,
    END
  }

  @Override
  public String toString() {
    return type == Type.END ? "end of expression" : "'" + text + "'";
  }
}
