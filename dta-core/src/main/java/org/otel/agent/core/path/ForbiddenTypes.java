package org.otel.agent.core.path;

import java.util.Set;

/**
 * Types a path may never read a property from (security, {@code docs/06-config-xml.md} §6).
 *
 * <p>A path calls getters, and the rule file is operator input. Most getters only return a value,
 * but on the types below a "getter" hands out a handle to the JVM itself or changes state: {@code
 * getClassLoader()}, {@code getConnection()} (takes a connection from the pool), {@code
 * getInputStream()}, {@code getAndIncrement()}, {@code getEncoded()} (key material). A value of
 * such a type, or of a subtype, has no properties as far as paths are concerned.
 *
 * <p>Names instead of {@code Class} literals: some of these types live in JDK modules that a
 * trimmed runtime image may not contain ({@code java.sql}).
 */
final class ForbiddenTypes {

  /** The property behind {@code Object.getClass()}: the way into reflection, never a value. */
  static final String CLASS_PROPERTY = "class";

  private static final Set<String> NAMES =
      Set.of(
          // reflection, class loading, threads and processes
          "java.lang.Class",
          "java.lang.ClassLoader",
          "java.lang.Module",
          "java.lang.ModuleLayer",
          "java.lang.Thread",
          "java.lang.ThreadGroup",
          "java.lang.Runtime",
          "java.lang.Process",
          "java.lang.ProcessBuilder",
          "java.lang.ProcessHandle",
          "java.lang.reflect.AccessibleObject",
          "java.lang.invoke.MethodHandle",
          "java.lang.invoke.MethodHandles$Lookup",
          "java.security.ProtectionDomain",
          // open resources: reading from them consumes or leaks them
          "java.io.InputStream",
          "java.io.OutputStream",
          "java.io.Reader",
          "java.io.Writer",
          "java.nio.channels.Channel",
          "java.net.Socket",
          "java.net.ServerSocket",
          "java.net.DatagramSocket",
          "java.net.URLConnection",
          "java.sql.Wrapper", // Connection, Statement, ResultSet, DataSource
          "javax.sql.CommonDataSource",
          // key material
          "java.security.Key",
          "java.security.KeyStore",
          // getAndIncrement() / getAndDecrement() change application state
          "java.util.concurrent.atomic.AtomicInteger",
          "java.util.concurrent.atomic.AtomicLong");

  private ForbiddenTypes() {}

  /** {@code true} when {@code type}, a superclass or an implemented interface is forbidden. */
  static boolean contains(final Class<?> type) {
    for (Class<?> current = type; current != null; current = current.getSuperclass()) {
      if (NAMES.contains(current.getName()) || anyInterfaceForbidden(current)) {
        return true;
      }
    }
    return false;
  }

  private static boolean anyInterfaceForbidden(final Class<?> type) {
    for (final Class<?> candidate : type.getInterfaces()) {
      if (NAMES.contains(candidate.getName()) || anyInterfaceForbidden(candidate)) {
        return true;
      }
    }
    return false;
  }
}
