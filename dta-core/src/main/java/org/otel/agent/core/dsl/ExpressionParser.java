package org.otel.agent.core.dsl;

import java.util.ArrayList;
import java.util.List;
import org.otel.agent.core.dsl.ConditionException.Category;
import org.otel.agent.core.dsl.Expression.ComparisonOperator;
import org.otel.agent.core.dsl.Expression.TextFunctionName;
import org.otel.agent.core.dsl.Operand.ArithmeticOperator;
import org.otel.agent.core.path.InvalidPathException;
import org.otel.agent.core.path.PathCompiler;

/**
 * Parses a {@code <when>} expression into an {@link Expression}. Recursive descent over this
 * grammar (lowest precedence first; see {@code docs/07-dsl.md}):
 *
 * <pre>
 * expression := or
 * or         := and ( 'or' and )*
 * and        := not ( 'and' not )*
 * not        := 'not' not | predicate
 * predicate  := '(' expression ')'
 *             | textFn '(' sum ',' sum ')'                 textFn = contains|icontains|like|ilike
 *             | sum ( compareOp sum | 'in' '[' literal ( ',' literal )* ']' )?
 * sum        := product ( ( '+' | '-' ) product )*
 * product    := unary ( ( '*' | '/' | '%' ) unary )*
 * unary      := '-' NUMBER | atom
 * atom       := NUMBER | STRING | 'true' | 'false' | 'null' | PATH
 *             | 'size' '(' sum ')' | '(' sum ')'
 * compareOp  := '==' | '!=' | 'lt' | 'lte' | 'gt' | 'gte'
 * </pre>
 *
 * A predicate without operator ({@code $arg0.electric}) is a {@link Expression.Truthy}.
 */
public final class ExpressionParser {

  private final List<Token> tokens;
  private int index;

  private ExpressionParser(final List<Token> tokens) {
    this.tokens = tokens;
  }

  public static Expression parse(final String expression) throws ConditionException {
    final ExpressionParser parser = new ExpressionParser(Lexer.tokenize(expression));
    final Expression result = parser.parseOr();
    parser.expect(Token.Type.END, "end of expression");
    return result;
  }

  private Expression parseOr() throws ConditionException {
    final List<Expression> terms = new ArrayList<>();
    terms.add(parseAnd());
    while (accept(Token.Type.OR)) {
      terms.add(parseAnd());
    }
    return terms.size() == 1 ? terms.get(0) : new Expression.Or(terms);
  }

  private Expression parseAnd() throws ConditionException {
    final List<Expression> terms = new ArrayList<>();
    terms.add(parseNot());
    while (accept(Token.Type.AND)) {
      terms.add(parseNot());
    }
    return terms.size() == 1 ? terms.get(0) : new Expression.And(terms);
  }

  private Expression parseNot() throws ConditionException {
    if (accept(Token.Type.NOT)) {
      return new Expression.Not(parseNot());
    }
    return parsePredicate();
  }

  private Expression parsePredicate() throws ConditionException {
    if (current().type() == Token.Type.LEFT_PAREN) {
      final Expression grouped = tryParseGroupedExpression();
      if (grouped != null) {
        return grouped;
      }
    }
    if (current().type() == Token.Type.IDENTIFIER) {
      final TextFunctionName function = TextFunctionName.byName(current().text());
      if (function != null) {
        return parseTextFunction(function);
      }
    }
    return parseComparison();
  }

  /**
   * {@code ( … )} at the start of a predicate is a grouped boolean expression, unless an operator
   * follows the closing parenthesis — then it was an arithmetic group such as {@code ($a + 1) gt 2}
   * and is parsed again as an operand. Parsing has no side effects, so backtracking is safe.
   */
  private Expression tryParseGroupedExpression() throws ConditionException {
    final int start = index;
    advance();
    final Expression inner = parseOr();
    expect(Token.Type.RIGHT_PAREN, "')'");
    if (startsOperatorAfterOperand(current().type())) {
      index = start;
      return null;
    }
    return inner;
  }

  private Expression parseTextFunction(final TextFunctionName function) throws ConditionException {
    final List<Operand> arguments = parseArguments();
    if (arguments.size() != 2) {
      throw new ConditionException(
          Category.WRONG_ARITY, function + " expects 2 arguments, got " + arguments.size());
    }
    return new Expression.TextFunction(function, arguments.get(0), arguments.get(1));
  }

  private Expression parseComparison() throws ConditionException {
    final Operand left = parseSum();
    final ComparisonOperator operator = comparisonOperator(current().type());
    if (operator != null) {
      advance();
      return new Expression.Comparison(operator, left, parseSum());
    }
    if (accept(Token.Type.IN)) {
      return new Expression.Membership(left, parseLiteralList());
    }
    return new Expression.Truthy(left);
  }

  private List<Operand.Literal> parseLiteralList() throws ConditionException {
    expect(Token.Type.LEFT_BRACKET, "'[' after 'in'");
    final List<Operand.Literal> literals = new ArrayList<>();
    literals.add(parseLiteral());
    while (accept(Token.Type.COMMA)) {
      literals.add(parseLiteral());
    }
    expect(Token.Type.RIGHT_BRACKET, "']'");
    return literals;
  }

  private Operand.Literal parseLiteral() throws ConditionException {
    final Operand operand = parseUnary();
    if (operand instanceof Operand.Literal literal) {
      return literal;
    }
    throw syntaxError("only literals are allowed in an 'in' list");
  }

  private Operand parseSum() throws ConditionException {
    Operand left = parseProduct();
    while (true) {
      final ArithmeticOperator operator =
          switch (current().type()) {
            case PLUS -> ArithmeticOperator.PLUS;
            case MINUS -> ArithmeticOperator.MINUS;
            default -> null;
          };
      if (operator == null) {
        return left;
      }
      advance();
      left = new Operand.Arithmetic(operator, left, parseProduct());
    }
  }

  private Operand parseProduct() throws ConditionException {
    Operand left = parseUnary();
    while (true) {
      final ArithmeticOperator operator =
          switch (current().type()) {
            case STAR -> ArithmeticOperator.TIMES;
            case SLASH -> ArithmeticOperator.DIVIDE;
            case PERCENT -> ArithmeticOperator.REMAINDER;
            default -> null;
          };
      if (operator == null) {
        return left;
      }
      advance();
      left = new Operand.Arithmetic(operator, left, parseUnary());
    }
  }

  private Operand parseUnary() throws ConditionException {
    if (accept(Token.Type.MINUS)) {
      if (current().type() != Token.Type.NUMBER) {
        throw syntaxError("'-' must be followed by a number");
      }
      return number("-" + advance().text());
    }
    return parseAtom();
  }

  private Operand parseAtom() throws ConditionException {
    final Token token = current();
    switch (token.type()) {
      case NUMBER:
        advance();
        return number(token.text());
      case STRING:
        advance();
        return new Operand.Literal(token.text());
      case TRUE:
        advance();
        return new Operand.Literal(Boolean.TRUE);
      case FALSE:
        advance();
        return new Operand.Literal(Boolean.FALSE);
      case NULL:
        advance();
        return new Operand.Literal(null);
      case PATH:
        advance();
        return reference(token);
      case LEFT_PAREN:
        advance();
        final Operand grouped = parseSum();
        expect(Token.Type.RIGHT_PAREN, "')'");
        return grouped;
      case IDENTIFIER:
        return parseValueFunction(token);
      default:
        throw syntaxError("unexpected " + token);
    }
  }

  private Operand parseValueFunction(final Token name) throws ConditionException {
    if (!"size".equals(name.text())) {
      throw new ConditionException(
          Category.UNKNOWN_FUNCTION,
          "'"
              + name.text()
              + "' is not a function (functions: size, contains, icontains, like, ilike)");
    }
    final List<Operand> arguments = parseArguments();
    if (arguments.size() != 1) {
      throw new ConditionException(
          Category.WRONG_ARITY, "size expects 1 argument, got " + arguments.size());
    }
    return new Operand.Size(arguments.get(0));
  }

  /** {@code name '(' sum (',' sum)* ')'}; the current token is the function name. */
  private List<Operand> parseArguments() throws ConditionException {
    final Token name = advance();
    expect(Token.Type.LEFT_PAREN, "'(' after " + name.text());
    final List<Operand> arguments = new ArrayList<>();
    arguments.add(parseSum());
    while (accept(Token.Type.COMMA)) {
      arguments.add(parseSum());
    }
    expect(Token.Type.RIGHT_PAREN, "')'");
    return arguments;
  }

  private static Operand.Reference reference(final Token token) throws ConditionException {
    try {
      return new Operand.Reference(PathCompiler.reference(token.text()));
    } catch (InvalidPathException invalid) {
      throw new ConditionException(
          Category.SYNTAX_ERROR, invalid.getMessage() + " at position " + token.position());
    }
  }

  private Operand.Literal number(final String text) throws ConditionException {
    try {
      return text.indexOf('.') >= 0
          ? new Operand.Literal(Double.parseDouble(text))
          : new Operand.Literal(Long.parseLong(text));
    } catch (NumberFormatException tooLarge) {
      throw syntaxError("number out of range: " + text);
    }
  }

  private static ComparisonOperator comparisonOperator(final Token.Type type) {
    return switch (type) {
      case EQUAL -> ComparisonOperator.EQUAL;
      case NOT_EQUAL -> ComparisonOperator.NOT_EQUAL;
      case LESS -> ComparisonOperator.LESS;
      case LESS_OR_EQUAL -> ComparisonOperator.LESS_OR_EQUAL;
      case GREATER -> ComparisonOperator.GREATER;
      case GREATER_OR_EQUAL -> ComparisonOperator.GREATER_OR_EQUAL;
      default -> null;
    };
  }

  private static boolean startsOperatorAfterOperand(final Token.Type type) {
    return comparisonOperator(type) != null
        || switch (type) {
          case IN, PLUS, MINUS, STAR, SLASH, PERCENT -> true;
          default -> false;
        };
  }

  private Token current() {
    return tokens.get(index);
  }

  private Token advance() {
    final Token token = tokens.get(index);
    if (index < tokens.size() - 1) {
      index++;
    }
    return token;
  }

  private boolean accept(final Token.Type type) {
    if (current().type() == type) {
      advance();
      return true;
    }
    return false;
  }

  private void expect(final Token.Type type, final String description) throws ConditionException {
    if (current().type() != type) {
      throw syntaxError("expected " + description + " but found " + current());
    }
    advance();
  }

  private ConditionException syntaxError(final String message) {
    return new ConditionException(
        Category.SYNTAX_ERROR, message + " at position " + current().position());
  }
}
