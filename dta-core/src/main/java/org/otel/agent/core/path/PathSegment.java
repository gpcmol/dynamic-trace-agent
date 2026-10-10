package org.otel.agent.core.path;

import java.util.Objects;

/**
 * One step of a path: a property, optionally followed by an index ({@code passengers[1]}).
 *
 * @param property the property name (getter {@code getX}/{@code isX} or public field {@code x})
 * @param index the element index, or {@link #NO_INDEX}
 */
public record PathSegment(String property, int index) {

  public static final int NO_INDEX = -1;

  public PathSegment {
    Objects.requireNonNull(property, "property");
    if (index < NO_INDEX) {
      throw new IllegalArgumentException("index must be >= 0 or NO_INDEX");
    }
  }

  public static PathSegment property(final String property) {
    return new PathSegment(property, NO_INDEX);
  }

  public static PathSegment indexed(final String property, final int index) {
    return new PathSegment(property, index);
  }

  public boolean isIndexed() {
    return index != NO_INDEX;
  }

  @Override
  public String toString() {
    return isIndexed() ? property + '[' + index + ']' : property;
  }
}
