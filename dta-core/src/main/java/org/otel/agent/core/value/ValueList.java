package org.otel.agent.core.value;

import java.util.List;
import java.util.Objects;

/**
 * A homogeneous list for an array attribute.
 *
 * @param elementType {@code String}, {@code Long}, {@code Double} or {@code Boolean}
 * @param values the elements, all of {@code elementType}, no {@code null}s
 */
public record ValueList(Class<?> elementType, List<Object> values) {

  public ValueList {
    Objects.requireNonNull(elementType, "elementType");
    values = List.copyOf(values);
  }
}
