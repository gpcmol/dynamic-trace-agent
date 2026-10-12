package org.otel.agent.core.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PropertyAccessorsTest {

  public static class Base {
    public int count = 3;

    public long getMileage() {
      return 42L;
    }
  }

  public static final class Derived extends Base {
    public List<String> getTags() {
      return List.of();
    }
  }

  @Test
  void propertyTypeFollowsDiscoveryOrder() {
    assertEquals(Optional.of(long.class), PropertyAccessors.propertyType(Derived.class, "mileage"));
    assertEquals(Optional.of(List.class), PropertyAccessors.propertyType(Derived.class, "tags"));
    assertEquals(Optional.of(int.class), PropertyAccessors.propertyType(Derived.class, "count"));
    assertEquals(Optional.empty(), PropertyAccessors.propertyType(Derived.class, "colour"));
  }

  @Test
  void readerIsDiscoveredOnceAndBoxesPrimitives() throws Throwable {
    final var first = PropertyAccessors.reader(Derived.class, "mileage");
    final var second = PropertyAccessors.reader(Derived.class, "mileage");

    assertSame(first, second);
    assertEquals(42L, (Object) first.invokeExact((Object) new Derived()));
  }

  @Test
  void missingPropertyHasNoReader() {
    assertNull(PropertyAccessors.reader(Derived.class, "colour"));
  }

  /** An application class that happens to be a stream: still a forbidden type. */
  public static final class Upload extends InputStream {
    public String getName() {
      return "upload";
    }

    @Override
    public int read() {
      return -1;
    }
  }

  @ParameterizedTest
  @CsvSource({
    "java.util.concurrent.atomic.AtomicInteger, andIncrement",
    "java.util.concurrent.atomic.AtomicLong, andDecrement",
    "java.lang.Class, classLoader",
    "java.lang.Class, name",
    "java.lang.Thread, contextClassLoader",
    "java.net.Socket, inputStream",
    "java.sql.Connection, metaData",
    "javax.sql.DataSource, connection",
    "java.security.PrivateKey, encoded",
    "org.otel.agent.core.path.PropertyAccessorsTest$Upload, name"
  })
  void forbiddenTypesHaveNoProperties(final String typeName, final String property)
      throws Exception {
    final Class<?> type = Class.forName(typeName);

    assertNull(PropertyAccessors.reader(type, property));
    assertEquals(Optional.empty(), PropertyAccessors.propertyType(type, property));
    assertTrue(PropertyAccessors.describeMissing(type, property).contains("protected type"));
  }

  @Test
  void getClassIsNeverAProperty() {
    assertNull(PropertyAccessors.reader(Derived.class, "class"));
    assertEquals(Optional.empty(), PropertyAccessors.propertyType(Derived.class, "class"));
  }

  @Test
  void ordinaryJdkValueTypesStayReadable() throws Throwable {
    assertEquals(
        2024,
        (Object)
            PropertyAccessors.reader(LocalDate.class, "year")
                .invokeExact((Object) LocalDate.of(2024, 5, 1)));
    assertEquals(
        "'colour' is not a property of " + Derived.class.getName(),
        PropertyAccessors.describeMissing(Derived.class, "colour"));
  }
}
