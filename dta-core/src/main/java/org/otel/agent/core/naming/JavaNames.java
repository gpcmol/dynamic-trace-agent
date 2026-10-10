package org.otel.agent.core.naming;

/** Syntax checks for Java identifiers and binary class names, as used in the configuration. */
public final class JavaNames {

  private JavaNames() {}

  /** {@code true} for a Java identifier such as {@code park} or {@code licensePlate}. */
  public static boolean isIdentifier(final String value) {
    if (value == null || value.isEmpty() || !Character.isJavaIdentifierStart(value.charAt(0))) {
      return false;
    }
    for (int i = 1; i < value.length(); i++) {
      if (!Character.isJavaIdentifierPart(value.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  /**
   * {@code true} for a binary class name such as {@code com.example.Garage} or {@code
   * com.example.Outer$Inner}: identifiers separated by single dots.
   */
  public static boolean isBinaryClassName(final String value) {
    if (value == null || value.isEmpty()) {
      return false;
    }
    for (final String part : value.split("\\.", -1)) {
      if (!isIdentifier(part)) {
        return false;
      }
    }
    return true;
  }
}
