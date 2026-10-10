package org.otel.agent.core.path;

/** Where a path starts: {@code $this}, {@code $return} or {@code $argN}. */
public sealed interface PathRoot {

  /** Highest supported argument index in {@code $argN}. */
  int MAX_ARGUMENT_INDEX = 127;

  PathRoot THIS = new This();
  PathRoot RETURN = new Return();

  /** The root value for one method exit. */
  Object select(ExitFrame frame);

  /** {@code $this}: the receiver. */
  record This() implements PathRoot {
    @Override
    public Object select(final ExitFrame frame) {
      return frame.self();
    }

    @Override
    public String toString() {
      return "$this";
    }
  }

  /** {@code $return}: the return value, {@code null} for void methods. */
  record Return() implements PathRoot {
    @Override
    public Object select(final ExitFrame frame) {
      return frame.returned();
    }

    @Override
    public String toString() {
      return "$return";
    }
  }

  /** {@code $argN}: the N-th argument (0-based). */
  record Argument(int index) implements PathRoot {
    public Argument {
      if (index < 0 || index > MAX_ARGUMENT_INDEX) {
        throw new IllegalArgumentException("argument index out of range: " + index);
      }
    }

    @Override
    public Object select(final ExitFrame frame) {
      return frame.argument(index);
    }

    @Override
    public String toString() {
      return "$arg" + index;
    }
  }
}
