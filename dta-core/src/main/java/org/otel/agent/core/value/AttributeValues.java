package org.otel.agent.core.value;

import java.lang.reflect.Array;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;

/**
 * Converts a resolved Java value to something a span attribute can hold.
 *
 * <p>Result is one of {@code String}, {@code Long}, {@code Double}, {@code Boolean}, {@link
 * ValueList}, or {@code null} meaning "write nothing". Rules (spec {@code attribute-enrichment}):
 *
 * <ul>
 *   <li>{@code String}, {@code Boolean}: as is. {@code Character}: string. Enum: {@code name()}.
 *   <li>{@code Byte}/{@code Short}/{@code Integer}/{@code Long}: long. {@code BigInteger}: long if
 *       it fits, otherwise nothing.
 *   <li>{@code Float}/{@code Double}/{@code BigDecimal}: double if finite, otherwise nothing.
 *   <li>Arrays and {@code Iterable}s: a {@link ValueList} if all non-null elements convert to the
 *       same type; {@code null} elements are dropped; an empty primitive/known array keeps its
 *       element type; mixed or unconvertible elements give nothing.
 *   <li>Anything else (domain objects, maps, …): nothing. There is no {@code toString()} fallback.
 * </ul>
 */
public final class AttributeValues {

  private AttributeValues() {}

  public static Object convert(final Object value) {
    if (value == null) {
      return null;
    }
    final Object scalar = convertScalar(value);
    if (scalar != null) {
      return scalar;
    }
    if (value.getClass().isArray()) {
      return convertArray(value);
    }
    if (value instanceof Iterable<?> iterable) {
      return convertIterable(iterable);
    }
    return null;
  }

  private static Object convertScalar(final Object value) {
    if (value instanceof String || value instanceof Boolean || value instanceof Long) {
      return value;
    }
    if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
      return ((Number) value).longValue();
    }
    if (value instanceof Double || value instanceof Float) {
      return finite(((Number) value).doubleValue());
    }
    if (value instanceof Character character) {
      return character.toString();
    }
    if (value instanceof Enum<?> constant) {
      return constant.name();
    }
    if (value instanceof BigInteger big) {
      return big.bitLength() < Long.SIZE ? big.longValue() : null;
    }
    if (value instanceof BigDecimal decimal) {
      return finite(decimal.doubleValue());
    }
    return null;
  }

  private static Double finite(final double value) {
    return Double.isFinite(value) ? value : null;
  }

  private static ValueList convertArray(final Object array) {
    final int length = Array.getLength(array);
    final List<Object> values = new ArrayList<>(length);
    Class<?> elementType = null;
    for (int i = 0; i < length; i++) {
      final Object element = Array.get(array, i);
      if (element == null) {
        continue;
      }
      final Object converted = convertScalar(element);
      if (converted == null || (elementType != null && converted.getClass() != elementType)) {
        return null;
      }
      elementType = converted.getClass();
      values.add(converted);
    }
    if (elementType == null) {
      elementType = attributeTypeOf(array.getClass().getComponentType());
    }
    return elementType == null ? null : new ValueList(elementType, values);
  }

  private static ValueList convertIterable(final Iterable<?> iterable) {
    final List<Object> values = new ArrayList<>();
    Class<?> elementType = null;
    for (final Object element : iterable) {
      if (element == null) {
        continue;
      }
      final Object converted = convertScalar(element);
      if (converted == null || (elementType != null && converted.getClass() != elementType)) {
        return null;
      }
      elementType = converted.getClass();
      values.add(converted);
    }
    // An empty collection has no knowable element type (erasure): write nothing.
    return elementType == null ? null : new ValueList(elementType, values);
  }

  /** The attribute element type a Java component type converts to, or {@code null}. */
  private static Class<?> attributeTypeOf(final Class<?> componentType) {
    if (componentType == String.class
        || componentType == char.class
        || componentType == Character.class
        || componentType.isEnum()) {
      return String.class;
    }
    if (componentType == boolean.class || componentType == Boolean.class) {
      return Boolean.class;
    }
    if (componentType == float.class
        || componentType == double.class
        || componentType == Float.class
        || componentType == Double.class
        || componentType == BigDecimal.class) {
      return Double.class;
    }
    if (componentType == byte.class
        || componentType == short.class
        || componentType == int.class
        || componentType == long.class
        || componentType == Byte.class
        || componentType == Short.class
        || componentType == Integer.class
        || componentType == Long.class
        || componentType == BigInteger.class) {
      return Long.class;
    }
    return null;
  }
}
