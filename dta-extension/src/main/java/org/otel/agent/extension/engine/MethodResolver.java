package org.otel.agent.extension.engine;

import java.lang.reflect.Method;
import org.otel.agent.core.binding.ClassHierarchy;
import org.otel.agent.extension.instrument.SiteRegistry.SiteInfo;

/** Finds the {@link Method} behind a site id in the hierarchy of a runtime class. Cold path. */
final class MethodResolver {

  private MethodResolver() {}

  /**
   * The method named by {@code site}, declared in {@code type}, a superclass or an interface
   * (default method) of its hierarchy; {@code null} if there is none.
   */
  static Method resolve(final Class<?> type, final SiteInfo site) {
    final Class<?> declaring = ClassHierarchy.byName(type).get(site.className());
    return declaring == null ? null : declaredMethod(declaring, site);
  }

  private static Method declaredMethod(final Class<?> type, final SiteInfo site) {
    for (final Method method : type.getDeclaredMethods()) {
      if (method.getName().equals(site.methodName())
          && descriptorOf(method).equals(site.descriptor())) {
        return method;
      }
    }
    return null;
  }

  /** The JVM descriptor, e.g. {@code (Lcom/example/Car;)Lcom/example/Result;}. */
  static String descriptorOf(final Method method) {
    final StringBuilder descriptor = new StringBuilder("(");
    for (final Class<?> parameter : method.getParameterTypes()) {
      descriptor.append(parameter.descriptorString());
    }
    return descriptor.append(')').append(method.getReturnType().descriptorString()).toString();
  }
}
