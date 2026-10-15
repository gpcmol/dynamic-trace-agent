package org.otel.agent.core.path;

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.util.Arrays;
import java.util.Optional;

/**
 * Just enough generics for static checks (spec {@code condition-dsl}): the element type of {@code
 * List<Tree>}, and type variables resolved through a class hierarchy ({@code Forest extends
 * Base<Tree>} makes {@code List<T>} of {@code Base<T>} a {@code List<Tree>}).
 *
 * <p>A type variable that cannot be resolved (a raw type, a method type variable) stands for its
 * first bound, a wildcard for its upper bound — never for a narrower type. JDK only; runs at bind
 * time, never on the hot path.
 */
public final class GenericTypes {

  private GenericTypes() {}

  /** The class a value of {@code type} is an instance of, at least. */
  public static Class<?> erase(final Type type) {
    if (type instanceof Class<?> plain) {
      return plain;
    }
    if (type instanceof ParameterizedType parameterized) {
      return erase(parameterized.getRawType());
    }
    if (type instanceof GenericArrayType array) {
      return erase(array.getGenericComponentType()).arrayType();
    }
    if (type instanceof WildcardType wildcard) {
      return erase(wildcard.getUpperBounds()[0]);
    }
    if (type instanceof TypeVariable<?> variable) {
      return erase(variable.getBounds()[0]);
    }
    return Object.class;
  }

  /**
   * The element type of an array or {@code Iterable} type; empty if {@code type} is neither. {@code
   * Object} when the element type is not in the code ({@code List<?>}, raw {@code List}).
   */
  public static Optional<Type> elementType(final Type type) {
    final Type upper = upperBound(type);
    if (upper instanceof GenericArrayType array) {
      return Optional.of(array.getGenericComponentType());
    }
    final Class<?> raw = erase(upper);
    if (raw.isArray()) {
      return Optional.of(raw.getComponentType());
    }
    if (Iterable.class.isAssignableFrom(raw)) {
      final Type view = supertype(upper, Iterable.class);
      return Optional.of(
          view instanceof ParameterizedType parameterized
              ? parameterized.getActualTypeArguments()[0]
              : Object.class);
    }
    return Optional.empty();
  }

  /**
   * {@code member} — a type used in a declaration of {@code declaringClass} — as seen from a value
   * of type {@code context}: the type variables of {@code declaringClass} are replaced by the
   * arguments {@code context} gives them.
   */
  public static Type resolve(final Type member, final Class<?> declaringClass, final Type context) {
    final Type view = supertype(upperBound(context), declaringClass);
    return view == null ? member : substitute(member, view);
  }

  /** {@code context} viewed as {@code target} with its type arguments, or {@code null}. */
  private static Type supertype(final Type context, final Class<?> target) {
    final Class<?> raw = erase(context);
    if (!target.isAssignableFrom(raw)) {
      return null;
    }
    if (raw == target) {
      return context;
    }
    final Type superclass = raw.getGenericSuperclass();
    if (superclass != null) {
      final Type found = supertype(substitute(superclass, context), target);
      if (found != null) {
        return found;
      }
    }
    for (final Type implemented : raw.getGenericInterfaces()) {
      final Type found = supertype(substitute(implemented, context), target);
      if (found != null) {
        return found;
      }
    }
    return null;
  }

  /** Replaces the type variables of {@code context}'s class by {@code context}'s arguments. */
  private static Type substitute(final Type type, final Type context) {
    if (!(context instanceof ParameterizedType parameterized)) {
      return type;
    }
    final TypeVariable<?>[] variables = erase(parameterized.getRawType()).getTypeParameters();
    return replace(type, variables, parameterized.getActualTypeArguments());
  }

  private static Type replace(
      final Type type, final TypeVariable<?>[] variables, final Type[] values) {
    if (type instanceof TypeVariable<?> variable) {
      for (int i = 0; i < variables.length && i < values.length; i++) {
        if (variables[i].equals(variable)) {
          return values[i];
        }
      }
      return variable;
    }
    if (type instanceof ParameterizedType parameterized) {
      final Type[] arguments = parameterized.getActualTypeArguments().clone();
      for (int i = 0; i < arguments.length; i++) {
        arguments[i] = replace(arguments[i], variables, values);
      }
      return new Parameterized(erase(parameterized.getRawType()), arguments);
    }
    if (type instanceof GenericArrayType array) {
      return new GenericArray(replace(array.getGenericComponentType(), variables, values));
    }
    if (type instanceof WildcardType wildcard) {
      // Only the upper bound matters for static checks: '? extends T' reads as T.
      return replace(wildcard.getUpperBounds()[0], variables, values);
    }
    return type;
  }

  private static Type upperBound(final Type type) {
    Type current = type;
    while (true) {
      if (current instanceof WildcardType wildcard) {
        current = wildcard.getUpperBounds()[0];
      } else if (current instanceof TypeVariable<?> variable) {
        current = variable.getBounds()[0];
      } else {
        return current;
      }
    }
  }

  /** A parameterized type built by substitution. */
  private record Parameterized(Class<?> raw, Type[] arguments) implements ParameterizedType {
    @Override
    public Type[] getActualTypeArguments() {
      return arguments.clone();
    }

    @Override
    public Type getRawType() {
      return raw;
    }

    @Override
    public Type getOwnerType() {
      return raw.getDeclaringClass();
    }

    @Override
    public boolean equals(final Object other) {
      return other instanceof ParameterizedType that
          && raw.equals(that.getRawType())
          && Arrays.equals(arguments, that.getActualTypeArguments());
    }

    @Override
    public int hashCode() {
      return raw.hashCode() ^ Arrays.hashCode(arguments);
    }

    @Override
    public String toString() {
      return raw.getName() + Arrays.toString(arguments).replace('[', '<').replace(']', '>');
    }
  }

  /** A generic array type built by substitution. */
  private record GenericArray(Type component) implements GenericArrayType {
    @Override
    public Type getGenericComponentType() {
      return component;
    }

    @Override
    public String toString() {
      return component.getTypeName() + "[]";
    }
  }
}
