package org.otel.agent.core.config;

import java.util.List;
import java.util.Optional;

/**
 * Packages that rules may never select: the JDK, OpenTelemetry, Byte Buddy and this agent.
 * Instrumenting them could break the JVM or the agent itself.
 */
final class ProtectedNamespaces {

  private static final List<String> PREFIXES =
      List.of(
          "java.",
          "javax.",
          "jdk.",
          "sun.",
          "com.sun.",
          "io.opentelemetry.",
          "net.bytebuddy.",
          "org.otel.agent.");

  private ProtectedNamespaces() {}

  /** The protected prefix the class name falls under, if any. */
  static Optional<String> protectedPrefixOf(final String className) {
    for (final String prefix : PREFIXES) {
      if (className.startsWith(prefix)) {
        return Optional.of(prefix);
      }
    }
    return Optional.empty();
  }
}
