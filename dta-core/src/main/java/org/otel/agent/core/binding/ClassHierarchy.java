package org.otel.agent.core.binding;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Questions about a loaded class that selectors need answered: which types it has, by name, and
 * whether it has a public method. Cold path only; results must not be stored (they hold {@code
 * Class} objects, see {@code docs/09-memory-safety.md}).
 */
public final class ClassHierarchy {

  private ClassHierarchy() {}

  /** The class, its superclasses and all their interfaces, by binary name. */
  public static Map<String, Class<?>> byName(final Class<?> type) {
    final Map<String, Class<?>> byName = new HashMap<>();
    final Deque<Class<?>> pending = new ArrayDeque<>();
    pending.add(type);
    while (!pending.isEmpty()) {
      final Class<?> current = pending.poll();
      if (byName.putIfAbsent(current.getName(), current) != null) {
        continue;
      }
      if (current.getSuperclass() != null) {
        pending.add(current.getSuperclass());
      }
      pending.addAll(List.of(current.getInterfaces()));
    }
    return byName;
  }

  /** {@code true} when {@code type} has a public method (declared or inherited) with that name. */
  public static boolean hasPublicMethod(final Class<?> type, final String name) {
    for (final Method method : type.getMethods()) {
      if (method.getName().equals(name)) {
        return true;
      }
    }
    return false;
  }
}
