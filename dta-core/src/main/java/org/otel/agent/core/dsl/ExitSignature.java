package org.otel.agent.core.dsl;

import java.util.List;
import java.util.Objects;

/**
 * The static types available to a condition at one instrumented method: the receiver type ({@code
 * $this}), the parameter types ({@code $argN}) and the return type ({@code $return}, {@code
 * void.class} for void methods).
 */
public record ExitSignature(Class<?> self, List<Class<?>> parameterTypes, Class<?> returnType) {

  public ExitSignature {
    Objects.requireNonNull(self, "self");
    parameterTypes = List.copyOf(parameterTypes);
    Objects.requireNonNull(returnType, "returnType");
  }
}
