package org.otel.agent.core.dsl;

import org.otel.agent.core.dsl.Expression.ComparisonOperator;
import org.otel.agent.core.path.ExitFrame;

/**
 * Comparisons. Null semantics (spec {@code condition-dsl}): only a comparison with the {@code null}
 * literal tests the reference ({@link NullCheck}); every other comparison with a {@code null}
 * operand is {@code false}.
 */
final class ComparisonConditions {

  private ComparisonConditions() {}

  /** {@code x == null} / {@code x != null}. */
  record NullCheck(ValueNode operand, boolean expectNull) implements Condition {
    @Override
    public boolean test(final ExitFrame frame) {
      return (operand.value(frame) == null) == expectNull;
    }
  }

  /** A boolean operand used as condition. */
  record Truthy(ValueNode operand) implements Condition {
    @Override
    public boolean test(final ExitFrame frame) {
      return Boolean.TRUE.equals(operand.value(frame));
    }
  }

  /** {@code == != lt lte gt gte} on numbers of the same normalised type. */
  record Numeric(ComparisonOperator operator, ValueNode left, ValueNode right, boolean floating)
      implements Condition {
    @Override
    public boolean test(final ExitFrame frame) {
      final Object leftValue = left.value(frame);
      final Object rightValue = right.value(frame);
      if (!(leftValue instanceof Number a) || !(rightValue instanceof Number b)) {
        return false;
      }
      final int order =
          floating
              ? Double.compare(a.doubleValue(), b.doubleValue())
              : Long.compare(a.longValue(), b.longValue());
      return switch (operator) {
        case EQUAL -> order == 0;
        case NOT_EQUAL -> order != 0;
        case LESS -> order < 0;
        case LESS_OR_EQUAL -> order <= 0;
        case GREATER -> order > 0;
        case GREATER_OR_EQUAL -> order >= 0;
      };
    }
  }

  /** {@code ==} / {@code !=} on strings, booleans or two values of the same class. */
  record Equality(boolean expectEqual, ValueNode left, ValueNode right) implements Condition {
    @Override
    public boolean test(final ExitFrame frame) {
      final Object leftValue = left.value(frame);
      final Object rightValue = right.value(frame);
      if (leftValue == null || rightValue == null) {
        return false;
      }
      return leftValue.equals(rightValue) == expectEqual;
    }
  }
}
