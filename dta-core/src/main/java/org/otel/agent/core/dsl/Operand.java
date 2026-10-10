package org.otel.agent.core.dsl;

import java.util.Objects;
import org.otel.agent.core.path.CompiledPath;

/** A value inside a condition: literal, path reference, {@code size(…)} or arithmetic. */
public sealed interface Operand {

  /**
   * A literal: {@code String}, {@code Long}, {@code Double}, {@code Boolean}, or {@code null} for
   * the {@code null} literal.
   */
  record Literal(Object value) implements Operand {
    public Literal {
      if (value != null
          && !(value instanceof String
              || value instanceof Long
              || value instanceof Double
              || value instanceof Boolean)) {
        throw new IllegalArgumentException("unsupported literal type: " + value.getClass());
      }
    }
  }

  /** A path such as {@code $arg0.brand}. */
  record Reference(CompiledPath path) implements Operand {
    public Reference {
      Objects.requireNonNull(path, "path");
    }
  }

  /** {@code size(operand)}: number of elements, 0 for {@code null}. */
  record Size(Operand operand) implements Operand {
    public Size {
      Objects.requireNonNull(operand, "operand");
    }
  }

  /** {@code left op right} with {@code + - * / %}. */
  record Arithmetic(ArithmeticOperator operator, Operand left, Operand right) implements Operand {
    public Arithmetic {
      Objects.requireNonNull(operator, "operator");
      Objects.requireNonNull(left, "left");
      Objects.requireNonNull(right, "right");
    }
  }

  enum ArithmeticOperator {
    PLUS,
    MINUS,
    TIMES,
    DIVIDE,
    REMAINDER
  }
}
