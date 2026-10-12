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
 *       element type; mixed or unconvertible elements give nothing. Only the first {@value
 *       #MAX_LIST_SIZE} elements are looked at.
 *   <li>Anything else (domain objects, maps, …): nothing. There is no {@code toString()} fallback.
 * </ul>
 */
public final class AttributeValues {

  /**
   * Most elements read from one array or collection. Bounds the work and the span size when a path
   * points at a huge (or endless, lazily loaded) collection.
   */
  public static final int MAX_LIST_SIZE = 1_000;

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
    final int length = Math.min(Array.getLength(array), MAX_LIST_SIZE);
    final List<Object> values = new ArrayList<>(length);
    for (int i = 0; i < length; i++) {
      if (!addElement(values, Array.get(array, i))) {
        return null;
      }
    }
    final Class<?> elementType =
        values.isEmpty()
            ? attributeTypeOf(array.getClass().getComponentType())
            : values.get(0).getClass();
    return elementType == null ? null : new ValueList(elementType, values);
  }

  private static ValueList convertIterable(final Iterable<?> iterable) {
    final List<Object> values = new ArrayList<>();
    int seen = 0;
    for (final Object element : iterable) {
      if (seen++ == MAX_LIST_SIZE) {
        break;
      }
      if (!addElement(values, element)) {
        return null;
      }
    }
    // An empty collection has no knowable element type (erasure): write nothing.
    return values.isEmpty() ? null : new ValueList(values.getFirst().getClass(), values);
  }

  /**
   * Adds the converted element ({@code null} is dropped); {@code false} when it cannot be converted
   * or has another type than the elements before it.
   */
  private static boolean addElement(final List<Object> values, final Object element) {
    if (element == null) {
      return true;
    }
    final Object converted = convertScalar(element);
    if (converted == null
        || (!values.isEmpty() && values.getFirst().getClass() != converted.getClass())) {
      return false;
    }
    values.add(converted);
    return true;
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
