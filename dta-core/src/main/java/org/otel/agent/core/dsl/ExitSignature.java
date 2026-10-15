package org.otel.agent.core.dsl;

import java.lang.reflect.Type;
import java.util.List;
import java.util.Objects;

/**
 * The static types available to a condition at one instrumented method: the receiver type ({@code
 * $this}), the parameter types ({@code $argN}) and the return type ({@code $return}, {@code
 * void.class} for void methods).
 *
 * @param genericParameterTypes the generic parameter types ({@code List<Tree>}), resolved against
 *     the receiver; same size as {@code parameterTypes}
 * @param genericReturnType the generic return type, resolved against the receiver
 */
public record ExitSignature(
    Class<?> self,
    List<Class<?>> parameterTypes,
    Class<?> returnType,
    List<Type> genericParameterTypes,
    Type genericReturnType) {

  public ExitSignature {
    Objects.requireNonNull(self, "self");
    parameterTypes = List.copyOf(parameterTypes);
    Objects.requireNonNull(returnType, "returnType");
    genericParameterTypes = List.copyOf(genericParameterTypes);
    Objects.requireNonNull(genericReturnType, "genericReturnType");
    if (genericParameterTypes.size() != parameterTypes.size()) {
      throw new IllegalArgumentException("generic and raw parameter types differ in number");
    }
  }

  /** A signature without generic information: the generic types are the raw classes. */
  public ExitSignature(
      final Class<?> self, final List<Class<?>> parameterTypes, final Class<?> returnType) {
    this(self, parameterTypes, returnType, List.<Type>copyOf(parameterTypes), returnType);
  }
}
