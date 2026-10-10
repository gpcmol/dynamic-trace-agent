package org.otel.agent.extension.instrument;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Set;
import net.bytebuddy.description.type.TypeDefinition;
import net.bytebuddy.description.type.TypeDescription;

/** Names of a type, its superclasses and all their interfaces, from Byte Buddy descriptions. */
final class TypeHierarchy {

  private TypeHierarchy() {}

  /**
   * All names in the hierarchy of {@code type}, starting with the type itself. Super types that
   * cannot be resolved (missing from the classpath) are skipped.
   */
  static Set<String> namesOf(final TypeDescription type) {
    final Set<String> names = new LinkedHashSet<>();
    final Deque<TypeDefinition> pending = new ArrayDeque<>();
    pending.add(type);
    while (!pending.isEmpty()) {
      final TypeDefinition current = pending.poll();
      try {
        if (!names.add(current.asErasure().getName())) {
          continue;
        }
        final TypeDefinition superClass = current.getSuperClass();
        if (superClass != null) {
          pending.add(superClass);
        }
        pending.addAll(current.getInterfaces());
      } catch (RuntimeException unresolvable) {
        // A super type that is not on the classpath cannot be a selected type.
      }
    }
    return names;
  }
}
