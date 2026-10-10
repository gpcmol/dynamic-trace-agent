package org.otel.agent.core.dsl;

/** Type normalisation used by the condition typer. */
final class Types {

  /** Marker type of the {@code null} literal. */
  static final Class<?> NULL = Void.class;

  private Types() {}

  /**
   * Integral types become {@code Long}, floating types {@code Double}, {@code boolean} becomes
   * {@code Boolean}; everything else is returned unchanged.
   */
  static Class<?> normalise(final Class<?> type) {
    if (type == long.class
        || type == int.class
        || type == short.class
        || type == byte.class
        || type == Long.class
        || type == Integer.class
        || type == Short.class
        || type == Byte.class) {
      return Long.class;
    }
    if (type == double.class
        || type == float.class
        || type == Double.class
        || type == Float.class) {
      return Double.class;
    }
    if (type == boolean.class) {
      return Boolean.class;
    }
    return type;
  }

  static boolean isNumeric(final Class<?> normalised) {
    return normalised == Long.class || normalised == Double.class;
  }

  static String describe(final Class<?> type) {
    return type == NULL ? "null" : type.getSimpleName();
  }
}
