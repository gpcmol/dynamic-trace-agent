package org.otel.agent.extension.bootstrap;

import java.lang.instrument.Instrumentation;
import java.util.Optional;

/**
 * Obtains the JVM {@link Instrumentation} from the OpenTelemetry agent, so no second {@code
 * -javaagent} is needed (spike S1).
 *
 * <p>Uses an <em>internal</em> OTel class, {@code
 * io.opentelemetry.javaagent.bootstrap.InstrumentationHolder}; this adapter is the only place that
 * knows about it. If a future agent version removes it, the extension runs in degraded mode (no
 * retransformation of already-loaded classes) instead of failing.
 */
final class InstrumentationAccess {

  private static final String HOLDER = "io.opentelemetry.javaagent.bootstrap.InstrumentationHolder";

  private InstrumentationAccess() {}

  static Optional<Instrumentation> fromOpenTelemetryAgent() {
    try {
      final Class<?> holder = Class.forName(HOLDER, false, null);
      final Object instrumentation = holder.getMethod("getInstrumentation").invoke(null);
      return instrumentation instanceof Instrumentation jvm && jvm.isRetransformClassesSupported()
          ? Optional.of(jvm)
          : Optional.empty();
    } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
      return Optional.empty();
    }
  }
}
