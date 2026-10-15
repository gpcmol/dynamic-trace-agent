package org.otel.agent.core.dsl;

import java.lang.reflect.Array;
import java.util.Collection;
import org.otel.agent.core.dsl.Operand.ArithmeticOperator;
import org.otel.agent.core.path.ExitFrame;
import org.otel.agent.core.path.PathReader;

/**
 * An executable operand. {@link #type()} is the normalised static type used by the typer: {@code
 * String}, {@code Long} (all integral types), {@code Double} (float/double), {@code Boolean},
 * {@link Types#NULL} for the {@code null} literal, {@code Object} when unknown, or the declared
 * class.
 */
interface ValueNode {

  Object value(ExitFrame frame);

  Class<?> type();

  /** A literal value. */
  record LiteralNode(Object constant, Class<?> type) implements ValueNode {
    @Override
    public Object value(final ExitFrame frame) {
      return constant;
    }
  }

  /** A path read with the binding's {@link PathReader}. */
  record PathNode(PathReader reader, Class<?> type) implements ValueNode {
    @Override
    public Object value(final ExitFrame frame) {
      return reader.read(frame);
    }
  }

  /**
   * A dynamic operand (static type {@code Object}) used as {@code type}: the value passes only if
   * it is of that type ({@code Long}: byte, short, int, long; {@code Double}: float, double),
   * anything else becomes {@code null}. Only built for dynamic operands, so typed paths never pay
   * for it.
   */
  record Coerced(ValueNode operand, Class<?> type) implements ValueNode {

    static boolean supports(final Class<?> type) {
      return type == String.class
          || type == Long.class
          || type == Double.class
          || type == Boolean.class;
    }

    public Coerced {
      if (!supports(type)) {
        throw new IllegalArgumentException("cannot coerce to " + type);
      }
    }

    @Override
    public Object value(final ExitFrame frame) {
      final Object value = operand.value(frame);
      if (type == Long.class) {
        return value instanceof Long
                || value instanceof Integer
                || value instanceof Short
                || value instanceof Byte
            ? value
            : null;
      }
      if (type == Double.class) {
        return value instanceof Double || value instanceof Float ? value : null;
      }
      return type.isInstance(value) ? value : null;
    }
  }

  /** {@code size(x)}: collection size or array length, 0 for {@code null}. */
  record SizeNode(ValueNode operand) implements ValueNode {
    @Override
    public Object value(final ExitFrame frame) {
      final Object value = operand.value(frame);
      if (value == null) {
        return 0L;
      }
      if (value instanceof Collection<?> collection) {
        return (long) collection.size();
      }
      if (value.getClass().isArray()) {
        return (long) Array.getLength(value);
      }
      return null;
    }

    @Override
    public Class<?> type() {
      return Long.class;
    }
  }

  /**
   * Arithmetic on longs or doubles. A {@code null} operand or integer division/remainder by zero
   * gives {@code null}.
   */
  record ArithmeticNode(
      ArithmeticOperator operator, ValueNode left, ValueNode right, boolean floating)
      implements ValueNode {

    @Override
    public Object value(final ExitFrame frame) {
      final Object leftValue = left.value(frame);
      final Object rightValue = right.value(frame);
      if (!(leftValue instanceof Number a) || !(rightValue instanceof Number b)) {
        return null;
      }
      return floating
          ? doubles(a.doubleValue(), b.doubleValue())
          : longs(a.longValue(), b.longValue());
    }

    private Object longs(final long a, final long b) {
      return switch (operator) {
        case PLUS -> a + b;
        case MINUS -> a - b;
        case TIMES -> a * b;
        case DIVIDE -> b == 0 ? null : a / b;
        case REMAINDER -> b == 0 ? null : a % b;
      };
    }

    private Object doubles(final double a, final double b) {
      return switch (operator) {
        case PLUS -> a + b;
        case MINUS -> a - b;
        case TIMES -> a * b;
        case DIVIDE -> a / b;
        case REMAINDER -> b == 0 ? null : a % b;
      };
    }

    @Override
    public Class<?> type() {
      return floating ? Double.class : Long.class;
    }
  }
}
