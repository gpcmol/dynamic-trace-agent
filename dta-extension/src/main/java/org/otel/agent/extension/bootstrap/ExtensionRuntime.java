package org.otel.agent.extension.bootstrap;

import io.opentelemetry.api.OpenTelemetry;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.otel.agent.core.plan.TransformationPlan;
import org.otel.agent.core.snapshot.Snapshot;
import org.otel.agent.extension.diag.AgentLog;
import org.otel.agent.extension.engine.EnrichmentEngine;
import org.otel.agent.extension.instrument.ActivePlan;
import org.otel.agent.extension.reload.ConfigFileWatcher;
import org.otel.agent.extension.reload.ReloadCoordinator;
import org.otel.agent.extension.reload.ReloadSettings;
import org.otel.agent.extension.reload.Retransformer;
import org.otel.agent.extension.telemetry.SpanWriter;
import org.otel.agent.gateway.EnrichmentGateway;

/**
 * Wires the components together once the gateway exists. The first configuration is loaded
 * synchronously, so rules are active before the application starts; after that one daemon thread
 * polls the file.
 *
 * <pre>
 *  ConfigFileWatcher ──▶ ReloadCoordinator ──▶ EnrichmentEngine (snapshot)  ◀── EnrichmentGateway
 *     (poller)               │                 ActivePlan (matchers)
 *                            └──────────────▶ Retransformer
 * </pre>
 */
final class ExtensionRuntime {

  private ExtensionRuntime() {}

  // The poller runs for the JVM's lifetime; failures are handled inside pollSafely.
  @SuppressWarnings("FutureReturnValueIgnored")
  static void start(
      final OpenTelemetry openTelemetry,
      final Optional<Instrumentation> instrumentation,
      final ReloadSettings settings,
      final AgentLog log) {
    final EnrichmentEngine engine =
        new EnrichmentEngine(new SpanWriter(openTelemetry.getTracer(SpanWriter.TRACER_NAME)), log);
    EnrichmentGateway.install(engine);

    final ReloadCoordinator coordinator =
        new ReloadCoordinator(
            new EngineTarget(engine), new Retransformer(instrumentation, log), log);
    final Path file = settings.configFile().orElseThrow();
    final ConfigFileWatcher watcher = new ConfigFileWatcher(file, coordinator, log);
    pollSafely(watcher, coordinator, log);

    final long seconds = settings.interval().toSeconds();
    newPollerThread()
        .scheduleWithFixedDelay(
            () -> pollSafely(watcher, coordinator, log), seconds, seconds, TimeUnit.SECONDS);
    log.debug(
        () ->
            "watching "
                + file
                + " every "
                + seconds
                + " s; retransformation "
                + (instrumentation.isPresent() ? "available" : "unavailable"));
  }

  private static void pollSafely(
      final ConfigFileWatcher watcher, final ReloadCoordinator coordinator, final AgentLog log) {
    try {
      coordinator.reportSelectorProblems();
      watcher.poll();
    } catch (Throwable failure) {
      // Anything escaping here would silently end the schedule: no reload would ever happen again.
      log.warn("configuration poll failed; retrying next interval", failure);
    }
  }

  /**
   * The single poller thread: a daemon without context classloader, so it can never keep an
   * application classloader alive (spec {@code memory-safety}).
   */
  private static ScheduledExecutorService newPollerThread() {
    return Executors.newSingleThreadScheduledExecutor(
        task -> {
          final Thread thread = new Thread(task, "dynamic-trace-agent-config");
          thread.setDaemon(true);
          thread.setContextClassLoader(null);
          return thread;
        });
  }

  /** Publishes to the engine (snapshot) and the instrumentation matchers (plan). */
  private record EngineTarget(EnrichmentEngine engine) implements ReloadCoordinator.Target {
    @Override
    public Snapshot activeSnapshot() {
      return engine.activeSnapshot();
    }

    @Override
    public void publish(final Snapshot snapshot) {
      engine.publish(snapshot);
    }

    @Override
    public void publishPlan(final TransformationPlan plan) {
      ActivePlan.set(plan);
    }
  }
}
