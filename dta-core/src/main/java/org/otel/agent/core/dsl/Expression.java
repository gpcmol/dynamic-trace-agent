package org.otel.agent.core.dsl;

import java.util.List;
import java.util.Objects;

/**
 * Parsed, untyped condition ({@code <when>}). Pure data without {@code Class} references, so it can
 * live in a configuration snapshot. {@link ConditionTyper} turns it into an executable {@link
 * Condition} for a concrete class.
 */
public sealed interface Expression {

  /** {@code a or b or …}. */
  record Or(List<Expression> terms) implements Expression {
    public Or {
      terms = List.copyOf(terms);
    }
  }

  /** {@code a and b and …}. */
  record And(List<Expression> terms) implements Expression {
    public And {
      terms = List.copyOf(terms);
    }
  }

  /** {@code not a}. */
  record Not(Expression term) implements Expression {
    public Not {
      Objects.requireNonNull(term, "term");
    }
  }

  /** {@code left op right} with {@code == != lt lte gt gte}. */
  record Comparison(ComparisonOperator operator, Operand left, Operand right)
      implements Expression {
    public Comparison {
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(left, "left");
      Objects.requireNonNull(right, "right");
    }
  }

  /** {@code value in [literal, …]}. */
  record Membership(Operand value, List<Operand.Literal> candidates) implements Expression {
    public Membership {
      Objects.requireNonNull(value, "value");
      candidates = List.copyOf(candidates);
    }
  }

  /** {@code contains / icontains / like / ilike (haystack, needle)}. */
  record TextFunction(TextFunctionName function, Operand haystack, Operand needle)
      implements Expression {
    public TextFunction {
      Objects.requireNonNull(function, "function");
      Objects.requireNonNull(haystack, "haystack");
      Objects.requireNonNull(needle, "needle");
    }
  }

  /** A boolean value used as condition, e.g. {@code $arg0.electric} or the literal {@code true}. */
  record Truthy(Operand operand) implements Expression {
    public Truthy {
      Objects.requireNonNull(operand, "operand");
    }
  }

  enum ComparisonOperator {
    EQUAL("=="),
    NOT_EQUAL("!="),
    LESS("lt"),
    LESS_OR_EQUAL("lte"),
    GREATER("gt"),
    GREATER_OR_EQUAL("gte");

    private final String symbol;

    ComparisonOperator(final String symbol) {
      this.symbol = symbol;
    }

    boolean isEquality() {
      return this == EQUAL || this == NOT_EQUAL;
    }

    @Override
    public String toString() {
      return symbol;
    }
  }

  enum TextFunctionName {
    CONTAINS("contains"),
    ICONTAINS("icontains"),
    LIKE("like"),
    ILIKE("ilike");

    private final String functionName;

    TextFunctionName(final String functionName) {
      this.functionName = functionName;
    }

    static TextFunctionName byName(final String name) {
      for (final TextFunctionName function : values()) {
        if (function.functionName.equals(name)) {
          return function;
        }
      }
      return null;
    }

    @Override
    public String toString() {
      return functionName;
    }
  }
}
