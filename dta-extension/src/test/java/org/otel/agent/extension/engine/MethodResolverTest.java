package org.otel.agent.extension.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.reflect.Method;
import org.junit.jupiter.api.Test;
import org.otel.agent.extension.instrument.SiteRegistry.SiteInfo;

class MethodResolverTest {

  interface Vehicle {
    default String describe(final String prefix) {
      return prefix;
    }
  }

  interface Motorised extends Vehicle {}

  static class Car implements Motorised {
    public String drive() {
      return "vroom";
    }
  }

  static class SportsCar extends Car {}

  @Test
  void findsAMethodDeclaredInASuperclass() throws Exception {
    final Method drive = Car.class.getDeclaredMethod("drive");

    assertEquals(drive, MethodResolver.resolve(SportsCar.class, site(drive)));
  }

  /** Advice woven into a default method reports the interface as its declaring class. */
  @Test
  void findsADefaultMethodOfAnInheritedInterface() throws Exception {
    final Method describe = Vehicle.class.getDeclaredMethod("describe", String.class);

    assertEquals(describe, MethodResolver.resolve(SportsCar.class, site(describe)));
  }

  @Test
  void unknownClassOrDescriptorGivesNothing() throws Exception {
    final Method drive = Car.class.getDeclaredMethod("drive");

    assertNull(MethodResolver.resolve(String.class, site(drive)));
    assertNull(
        MethodResolver.resolve(
            SportsCar.class, new SiteInfo(Car.class.getName(), "drive", "(I)V")));
  }

  private static SiteInfo site(final Method method) {
    return new SiteInfo(
        method.getDeclaringClass().getName(),
        method.getName(),
        MethodResolver.descriptorOf(method));
  }
}
