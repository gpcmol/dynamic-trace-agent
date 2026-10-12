package org.otel.agent.core.value;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class AttributeValuesTest {

  enum Fuel {
    PETROL
  }

  static Stream<Arguments> scalars() {
    return Stream.of(
        Arguments.of("bmw", "bmw"),
        Arguments.of(true, true),
        Arguments.of(7L, 7L),
        Arguments.of(7, 7L),
        Arguments.of((short) 7, 7L),
        Arguments.of((byte) 7, 7L),
        Arguments.of(1.5d, 1.5d),
        Arguments.of(1.5f, 1.5d),
        Arguments.of('x', "x"),
        Arguments.of(Fuel.PETROL, "PETROL"),
        Arguments.of(BigInteger.valueOf(Long.MAX_VALUE), Long.MAX_VALUE),
        Arguments.of(new BigDecimal("2.25"), 2.25d));
  }

  @ParameterizedTest
  @MethodSource("scalars")
  void convertsScalars(final Object input, final Object expected) {
    assertEquals(expected, AttributeValues.convert(input));
  }

  static Stream<Object> skipped() {
    return Stream.of(
        Double.NaN,
        Float.POSITIVE_INFINITY,
        BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE),
        new BigDecimal("1e400"),
        Duration.ofSeconds(1),
        new Object(),
        Map.of("a", "b"));
  }

  @ParameterizedTest
  @MethodSource("skipped")
  void skipsUnsupportedValues(final Object input) {
    assertNull(AttributeValues.convert(input));
  }

  @Test
  void nullIsNothing() {
    assertNull(AttributeValues.convert(null));
  }

  @Test
  void homogeneousListBecomesValueList() {
    assertEquals(
        new ValueList(String.class, List.of("Jan", "Piet")),
        AttributeValues.convert(Arrays.asList("Jan", null, "Piet")));
  }

  @Test
  void intArrayBecomesLongList() {
    assertEquals(
        new ValueList(Long.class, List.of(1L, 2L)), AttributeValues.convert(new int[] {1, 2}));
  }

  @Test
  void mixedTypesGiveNothing() {
    assertNull(AttributeValues.convert(List.of("a", 1)));
    assertNull(AttributeValues.convert(List.of(1, 1.5d)));
  }

  @Test
  void collectionOfObjectsGivesNothing() {
    assertNull(AttributeValues.convert(List.of(new Object())));
  }

  @Test
  void emptyTypedArrayKeepsElementType() {
    assertEquals(new ValueList(Double.class, List.of()), AttributeValues.convert(new double[0]));
    assertEquals(new ValueList(String.class, List.of()), AttributeValues.convert(new Fuel[0]));
    assertEquals(new ValueList(Boolean.class, List.of()), AttributeValues.convert(new boolean[0]));
    assertEquals(new ValueList(Long.class, List.of()), AttributeValues.convert(new Integer[0]));
  }

  @Test
  void emptyArrayOfUnknownTypeGivesNothing() {
    assertNull(AttributeValues.convert(new Object[0]));
  }

  @Test
  void emptyCollectionGivesNothing() {
    assertNull(AttributeValues.convert(Set.of()));
  }

  @Test
  void longListsAreTruncated() {
    final int limit = AttributeValues.MAX_LIST_SIZE;

    assertEquals(
        limit,
        ((ValueList) AttributeValues.convert(Collections.nCopies(limit + 1, "x"))).values().size());
    assertEquals(limit, ((ValueList) AttributeValues.convert(new int[limit + 1])).values().size());
  }

  @Test
  void endlessIterableTerminates() {
    final Iterable<String> endless = () -> Stream.generate(() -> "x").iterator();

    assertEquals(
        AttributeValues.MAX_LIST_SIZE,
        ((ValueList) AttributeValues.convert(endless)).values().size());
  }
}
