package org.otel.agent.core.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class LexerTest {

  private static List<Token.Type> types(final String input) throws ConditionException {
    return Lexer.tokenize(input).stream().map(Token::type).toList();
  }

  @Test
  void tokenizesWordOperatorsPathsAndLiterals() throws Exception {
    assertEquals(
        List.of(
            Token.Type.PATH,
            Token.Type.EQUAL,
            Token.Type.STRING,
            Token.Type.AND,
            Token.Type.NOT,
            Token.Type.PATH,
            Token.Type.GREATER_OR_EQUAL,
            Token.Type.NUMBER,
            Token.Type.END),
        types("$arg0.brand == 'BMW' and not $arg0.passengers[1].age gte 18"));
  }

  @Test
  void stringLiteralsUseEitherQuoteWithoutEscapes() throws Exception {
    final List<Token> tokens = Lexer.tokenize("\"it's\" 'say \"hi\"'");

    assertEquals("it's", tokens.get(0).text());
    assertEquals("say \"hi\"", tokens.get(1).text());
  }

  @Test
  void numbersAreIntegerOrDecimal() throws Exception {
    final List<Token> tokens = Lexer.tokenize("42 3.14");

    assertEquals("42", tokens.get(0).text());
    assertEquals("3.14", tokens.get(1).text());
  }

  @Test
  void keywordsAreCaseSensitive() throws Exception {
    assertEquals(Token.Type.IDENTIFIER, types("AND").get(0));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = ';',
      quoteCharacter = '`',
      value = {
        "$a && $b ; use 'and'",
        "$a || $b ; use 'or'",
        "!$a ; use 'not'",
        "$a < 1 ; use 'lt' or 'lte'",
        "$a >= 1 ; use 'gt' or 'gte'",
        "$a = 1 ; use '=='",
        "'open ; unclosed string",
        "1. ; after the decimal point",
        "$a # 1 ; unexpected character '#'"
      })
  void rejectsWithHelpfulMessage(final String input, final String hint) {
    final ConditionException exception =
        assertThrows(ConditionException.class, () -> Lexer.tokenize(input));

    assertEquals(ConditionException.Category.SYNTAX_ERROR, exception.category());
    assertTrue(exception.getMessage().contains(hint), exception.getMessage());
    assertTrue(exception.getMessage().contains("at position"), exception.getMessage());
  }
}
