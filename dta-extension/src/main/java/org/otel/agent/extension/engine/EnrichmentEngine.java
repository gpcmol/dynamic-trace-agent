package org.otel.agent.extension.engine;

import org.otel.agent.core.snapshot.Snapshot;
import org.otel.agent.extension.diag.AgentLog;
import org.otel.agent.extension.telemetry.SpanWriter;
import org.otel.agent.gateway.ExitHandler;

/**
 * The engine behind the gateway: receives every exit of an instrumented method and enriches the
 * span when a rule applies (spec {@code attribute-enrichment}).
 *
 * <p>Hot path, in order: one volatile read of the published state · class binding lookup · per-
 * thread frame (also the re-entrancy guard) · conditions · attributes. Nothing here throws into the
 * application; nothing is allocated for a selected method whose conditions are false.
 */
public final class EnrichmentEngine implements ExitHandler {

  private final ClassBindings bindings;
  private final SpanWriter writer;
  private final AgentLog log;
  private volatile EngineState state = EngineState.DISABLED;

  public EnrichmentEngine(final SpanWriter writer, final AgentLog log) {
    this.writer = writer;
    this.log = log;
    this.bindings = new ClassBindings(log);
  }

  /**
   * Makes {@code snapshot} the active configuration. In-flight calls finish with the configuration
   * they started with; the next call of each class rebinds once.
   */
  public void publish(final Snapshot snapshot) {
    state = EngineState.of(snapshot);
  }

  public Snapshot activeSnapshot() {
    return state.snapshot();
  }

  @Override
  public void onExit(
      final int site,
      final Object self,
      final Object returned,
      final Object a0,
      final Object a1,
      final Object a2,
      final Object a3) {
    final EngineState current = state;
    if (!current.enabled() || self == null) {
      return;
    }
    final MethodEnrichment enrichment = enrichmentFor(self, site, current);
    if (enrichment.isEmpty()) {
      return;
    }
    final ExitFrameSlot frame = ExitFrameSlot.tryAcquire();
    if (frame == null) {
      return;
    }
    try {
      enrichment.enrich(frame.fill(self, returned, a0, a1, a2, a3), current, writer);
    } catch (RuntimeException | LinkageError failure) {
      log.debug(() -> "enrichment failed: " + failure);
    } finally {
      frame.release();
    }
  }

  @Override
  public void onExitAllArgs(
      final int site, final Object self, final Object returned, final Object[] arguments) {
    final EngineState current = state;
    if (!current.enabled() || self == null) {
      return;
    }
    final MethodEnrichment enrichment = enrichmentFor(self, site, current);
    if (enrichment.isEmpty()) {
      return;
    }
    final ExitFrameSlot frame = ExitFrameSlot.tryAcquire();
    if (frame == null) {
      return;
    }
    try {
      enrichment.enrich(frame.fill(self, returned, arguments), current, writer);
    } catch (RuntimeException | LinkageError failure) {
      log.debug(() -> "enrichment failed: " + failure);
    } finally {
      frame.release();
    }
  }

  private MethodEnrichment enrichmentFor(
      final Object self, final int site, final EngineState current) {
    try {
      return bindings.lookup(self.getClass(), site, current);
    } catch (RuntimeException | LinkageError failure) {
      log.debug(() -> "binding failed for site " + site + ": " + failure);
      return MethodEnrichment.NONE;
    }
  }
}
