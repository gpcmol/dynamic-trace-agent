package org.otel.agent.extension.engine;

import org.otel.agent.core.snapshot.Snapshot;

/** Test access to engine operations that production code performs through the coordinator. */
public final class EngineAccess {

  private EngineAccess() {}

  /** Publishes the disabled configuration, as a removed configuration file does. */
  public static void disable(final EnrichmentEngine engine) {
    engine.publish(Snapshot.disabled());
  }
}
