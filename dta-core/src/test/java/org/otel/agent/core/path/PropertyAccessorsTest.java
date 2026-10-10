package org.otel.agent.core.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

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
}
