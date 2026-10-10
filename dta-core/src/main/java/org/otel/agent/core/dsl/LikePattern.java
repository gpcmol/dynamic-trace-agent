package org.otel.agent.core.dsl;

/**
 * SQL {@code LIKE} matching: {@code %} matches any sequence (also empty), {@code _} exactly one
 * character. Compiled once per literal pattern. {@code ilike} folds ASCII letters only, which is
 * intended for codes such as brands, tenants and regions.
 */
final class LikePattern {

  private final String[] parts;
  private final boolean ignoreCase;

  private LikePattern(final String pattern, final boolean ignoreCase) {
    this.parts = pattern.split("%", -1);
    this.ignoreCase = ignoreCase;
  }

  static LikePattern compile(final String pattern, final boolean ignoreCase) {
    return new LikePattern(pattern, ignoreCase);
  }

  boolean matches(final String value) {
    if (parts.length == 1) {
      return value.length() == parts[0].length() && matchesAt(value, 0, parts[0]);
    }
    final String first = parts[0];
    final String last = parts[parts.length - 1];
    if (!matchesAt(value, 0, first)) {
      return false;
    }
    final int lastStart = value.length() - last.length();
    if (lastStart < first.length() || !matchesAt(value, lastStart, last)) {
      return false;
    }
    int position = first.length();
    for (int i = 1; i < parts.length - 1; i++) {
      final String middle = parts[i];
      final int found = find(value, middle, position, lastStart);
      if (found < 0) {
        return false;
      }
      position = found + middle.length();
    }
    return true;
  }

  /** Leftmost match of {@code part} starting in {@code [from, limit - part.length()]}. */
  private int find(final String value, final String part, final int from, final int limit) {
    for (int start = from; start + part.length() <= limit; start++) {
      if (matchesAt(value, start, part)) {
        return start;
      }
    }
    return -1;
  }

  private boolean matchesAt(final String value, final int offset, final String part) {
    if (offset < 0 || offset + part.length() > value.length()) {
      return false;
    }
    for (int i = 0; i < part.length(); i++) {
      final char expected = part.charAt(i);
      if (expected != '_' && !same(expected, value.charAt(offset + i))) {
        return false;
      }
    }
    return true;
  }

  private boolean same(final char a, final char b) {
    return a == b || (ignoreCase && asciiLower(a) == asciiLower(b));
  }

  private static char asciiLower(final char c) {
    return c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c;
  }
}
