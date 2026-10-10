package org.otel.agent.core.dsl;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.otel.agent.core.dsl.ConditionException.Category;

/**
 * Splits a condition into tokens.
 *
 * <p>All operators are XML-safe words ({@code and or not lt lte gt gte in}) or symbols that need no
 * escaping ({@code == != + - * / %}). The C-style forms {@code && || ! < > <= >=} are rejected with
 * a message that names the word to use instead. String literals use single or double quotes and
 * have no escapes. A path ({@code $arg0.passengers[1].name}) is one token.
 */
final class Lexer {

  private static final Map<String, Token.Type> KEYWORDS =
      Map.ofEntries(
          Map.entry("and", Token.Type.AND),
          Map.entry("or", Token.Type.OR),
          Map.entry("not", Token.Type.NOT),
          Map.entry("in", Token.Type.IN),
          Map.entry("lt", Token.Type.LESS),
          Map.entry("lte", Token.Type.LESS_OR_EQUAL),
          Map.entry("gt", Token.Type.GREATER),
          Map.entry("gte", Token.Type.GREATER_OR_EQUAL),
          Map.entry("true", Token.Type.TRUE),
          Map.entry("false", Token.Type.FALSE),
          Map.entry("null", Token.Type.NULL));

  private final String input;
  private final List<Token> tokens = new ArrayList<>();
  private int position;

  private Lexer(final String input) {
    this.input = input;
  }

  static List<Token> tokenize(final String input) throws ConditionException {
    return new Lexer(input).run();
  }

  private List<Token> run() throws ConditionException {
    while (position < input.length()) {
      final char c = input.charAt(position);
      if (Character.isWhitespace(c)) {
        position++;
      } else if (c == '\'' || c == '"') {
        readString(c);
      } else if (isAsciiDigit(c)) {
        readNumber();
      } else if (c == '$') {
        readPath();
      } else if (Character.isJavaIdentifierStart(c)) {
        readWord();
      } else {
        readSymbol(c);
      }
    }
    tokens.add(new Token(Token.Type.END, "", input.length()));
    return tokens;
  }

  private void readString(final char quote) throws ConditionException {
    final int start = position;
    final int end = input.indexOf(quote, start + 1);
    if (end < 0) {
      throw syntaxError("unclosed string starting", start);
    }
    tokens.add(new Token(Token.Type.STRING, input.substring(start + 1, end), start));
    position = end + 1;
  }

  private void readNumber() throws ConditionException {
    final int start = position;
    skipDigits();
    if (position < input.length() && input.charAt(position) == '.') {
      position++;
      if (position >= input.length() || !isAsciiDigit(input.charAt(position))) {
        throw syntaxError("digits expected after the decimal point", position);
      }
      skipDigits();
    }
    tokens.add(new Token(Token.Type.NUMBER, input.substring(start, position), start));
  }

  private void skipDigits() {
    while (position < input.length() && isAsciiDigit(input.charAt(position))) {
      position++;
    }
  }

  /** A path runs until a character that cannot be part of {@code $root.a[1].b}. */
  private void readPath() {
    final int start = position;
    position++;
    while (position < input.length() && isPathCharacter(input.charAt(position))) {
      position++;
    }
    tokens.add(new Token(Token.Type.PATH, input.substring(start, position), start));
  }

  private void readWord() {
    final int start = position;
    position++;
    while (position < input.length() && Character.isJavaIdentifierPart(input.charAt(position))) {
      position++;
    }
    final String word = input.substring(start, position);
    tokens.add(new Token(KEYWORDS.getOrDefault(word, Token.Type.IDENTIFIER), word, start));
  }

  private void readSymbol(final char c) throws ConditionException {
    final int start = position;
    final char next = position + 1 < input.length() ? input.charAt(position + 1) : '\0';
    switch (c) {
      case '(' -> single(Token.Type.LEFT_PAREN, start);
      case ')' -> single(Token.Type.RIGHT_PAREN, start);
      case '[' -> single(Token.Type.LEFT_BRACKET, start);
      case ']' -> single(Token.Type.RIGHT_BRACKET, start);
      case ',' -> single(Token.Type.COMMA, start);
      case '+' -> single(Token.Type.PLUS, start);
      case '-' -> single(Token.Type.MINUS, start);
      case '*' -> single(Token.Type.STAR, start);
      case '/' -> single(Token.Type.SLASH, start);
      case '%' -> single(Token.Type.PERCENT, start);
      case '=' -> {
        if (next != '=') {
          throw syntaxError("'=' is not an operator, use '=='", start);
        }
        pair(Token.Type.EQUAL, "==", start);
      }
      case '!' -> {
        if (next != '=') {
          throw syntaxError("'!' is not supported, use 'not'", start);
        }
        pair(Token.Type.NOT_EQUAL, "!=", start);
      }
      case '&' -> throw syntaxError("'&&' is not supported, use 'and'", start);
      case '|' -> throw syntaxError("'||' is not supported, use 'or'", start);
      case '<' -> throw syntaxError("'<' is not supported, use 'lt' or 'lte'", start);
      case '>' -> throw syntaxError("'>' is not supported, use 'gt' or 'gte'", start);
      default -> throw syntaxError("unexpected character '" + c + "'", start);
    }
  }

  private void single(final Token.Type type, final int start) {
    tokens.add(new Token(type, String.valueOf(input.charAt(start)), start));
    position++;
  }

  private void pair(final Token.Type type, final String text, final int start) {
    tokens.add(new Token(type, text, start));
    position += 2;
  }

  private static boolean isAsciiDigit(final char c) {
    return c >= '0' && c <= '9';
  }

  private static boolean isPathCharacter(final char c) {
    return Character.isJavaIdentifierPart(c) || c == '.' || c == '[' || c == ']';
  }

  private static ConditionException syntaxError(final String message, final int position) {
    return new ConditionException(Category.SYNTAX_ERROR, message + " at position " + position);
  }
}
