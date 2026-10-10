package org.otel.agent.extension.engine;

import java.lang.reflect.Method;
import org.otel.agent.extension.instrument.SiteRegistry.SiteInfo;

/** Finds the {@link Method} behind a site id in the hierarchy of a runtime class. Cold path. */
final class MethodResolver {

  private MethodResolver() {}

  /** The declared method named by {@code site} in {@code type} or a superclass, or {@code null}. */
  static Method resolve(final Class<?> type, final SiteInfo site) {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      if (current.getName().equals(site.className())) {
        return declaredMethod(current, site);
      }
    }
    for (final Class<?> candidate : type.getInterfaces()) {
      if (candidate.getName().equals(site.className())) {
        return declaredMethod(candidate, site);
      }
    }
    return null;
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
