package org.otel.agent.core.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.otel.agent.core.dsl.ConditionException.Category;
import org.otel.agent.core.dsl.Expression.And;
import org.otel.agent.core.dsl.Expression.Comparison;
import org.otel.agent.core.dsl.Expression.ComparisonOperator;
import org.otel.agent.core.dsl.Expression.Membership;
import org.otel.agent.core.dsl.Expression.Not;
import org.otel.agent.core.dsl.Expression.Or;
import org.otel.agent.core.dsl.Expression.TextFunction;
import org.otel.agent.core.dsl.Expression.TextFunctionName;
import org.otel.agent.core.dsl.Expression.Truthy;
import org.otel.agent.core.dsl.Operand.Arithmetic;
import org.otel.agent.core.dsl.Operand.ArithmeticOperator;
import org.otel.agent.core.dsl.Operand.Literal;
import org.otel.agent.core.dsl.Operand.Reference;
import org.otel.agent.core.dsl.Operand.Size;

class ExpressionParserTest {

  private static Expression parse(final String text) throws ConditionException {
    return ExpressionParser.parse(text);
  }

  @Test
  void andBindsStrongerThanOr() throws Exception {
    final Or or = assertInstanceOf(Or.class, parse("$arg0.x or $arg0.y and $arg0.z"));

    assertInstanceOf(Truthy.class, or.terms().get(0));
    assertEquals(2, assertInstanceOf(And.class, or.terms().get(1)).terms().size());
  }

  @Test
  void parenthesesGroupBooleans() throws Exception {
    final And and = assertInstanceOf(And.class, parse("($arg0.x or $arg0.y) and $arg0.z"));

    assertInstanceOf(Or.class, and.terms().get(0));
  }

  @Test
  void notAppliesToTheNextTerm() throws Exception {
    final And and = assertInstanceOf(And.class, parse("not $arg0.electric and $arg0.big"));

    assertInstanceOf(Not.class, and.terms().get(0));
  }

  @Test
  void comparisonOperators() throws Exception {
    for (final ComparisonOperator operator : ComparisonOperator.values()) {
      final Comparison comparison =
          assertInstanceOf(Comparison.class, parse("$arg0.mileage " + operator + " 10"));
      assertEquals(operator, comparison.operator());
    }
  }

  @Test
  void multiplicationBindsStrongerThanAddition() throws Exception {
    final Comparison comparison = assertInstanceOf(Comparison.class, parse("$arg0.x + 2 * 3 == 7"));
    final Arithmetic sum = assertInstanceOf(Arithmetic.class, comparison.left());

    assertEquals(ArithmeticOperator.PLUS, sum.operator());
    assertEquals(
        ArithmeticOperator.TIMES, assertInstanceOf(Arithmetic.class, sum.right()).operator());
  }

  @Test
  void parenthesisedArithmeticOnTheLeftOfAComparison() throws Exception {
    final Comparison comparison =
        assertInstanceOf(Comparison.class, parse("($arg0.mileage + 1) * 2 gt 10"));

    assertEquals(
        ArithmeticOperator.TIMES, assertInstanceOf(Arithmetic.class, comparison.left()).operator());
  }

  @Test
  void negativeNumberLiteral() throws Exception {
    final Comparison comparison = assertInstanceOf(Comparison.class, parse("$arg0.x gt -5"));

    assertEquals(new Literal(-5L), comparison.right());
  }

  @Test
  void binaryMinusIsNotANegativeLiteral() throws Exception {
    final Comparison comparison = assertInstanceOf(Comparison.class, parse("$arg0.x - 1 gt 0"));

    assertEquals(
        ArithmeticOperator.MINUS, assertInstanceOf(Arithmetic.class, comparison.left()).operator());
  }

  @Test
  void membershipWithLiteralList() throws Exception {
    final Membership membership =
        assertInstanceOf(
            Membership.class,
            parse(
"""
$arg0.brand in ['bmw', "audi", null]"""));

    assertEquals(
        List.of(new Literal("bmw"), new Literal("audi"), new Literal(null)),
        membership.candidates());
  }

  @Test
  void textFunctionsAndSize() throws Exception {
    final TextFunction like =
        assertInstanceOf(TextFunction.class, parse("ilike($arg0.model, 'x%')"));
    assertEquals(TextFunctionName.ILIKE, like.function());

    final Comparison size =
        assertInstanceOf(Comparison.class, parse("size($arg0.passengers) gt 2"));
    assertInstanceOf(Size.class, size.left());
  }

  @Test
  void bareRootReference() throws Exception {
    final Comparison comparison = assertInstanceOf(Comparison.class, parse("$return == null"));

    assertInstanceOf(Reference.class, comparison.left());
  }

  @Test
  void literalTrue() throws Exception {
    assertEquals(new Truthy(new Literal(true)), parse("true"));
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value = {
        "$arg0.x == | SYNTAX_ERROR",
        "($arg0.x | SYNTAX_ERROR",
        "$arg0.x == 1 extra | SYNTAX_ERROR",
        "$arg0.x in 'bmw' | SYNTAX_ERROR",
        "$arg0.x in [$arg0.y] | SYNTAX_ERROR",
        "$arg0..x == 1 | SYNTAX_ERROR",
        "$self.x == 1 | SYNTAX_ERROR",
        "- $arg0.x gt 1 | SYNTAX_ERROR",
        "99999999999999999999 gt 1 | SYNTAX_ERROR",
        "length($arg0.x) gt 1 | UNKNOWN_FUNCTION",
        "size($arg0.x, $arg0.y) gt 1 | WRONG_ARITY",
        "like($arg0.x) | WRONG_ARITY",
        "contains($arg0.x, 'a', 'b') | WRONG_ARITY"
      })
  void rejects(final String text, final Category category) {
    final ConditionException exception = assertThrows(ConditionException.class, () -> parse(text));

    assertEquals(category, exception.category(), exception.getMessage());
  }
}
