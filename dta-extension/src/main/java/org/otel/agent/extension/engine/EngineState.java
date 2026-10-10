package org.otel.agent.extension.engine;

import io.opentelemetry.api.common.Attributes;
import java.util.HashMap;
import java.util.Map;
import org.otel.agent.core.snapshot.CompiledRule;
import org.otel.agent.core.snapshot.CompiledRule.CompiledAttribute;
import org.otel.agent.core.snapshot.Snapshot;
import org.otel.agent.extension.telemetry.AttributeKeySet;
import org.otel.agent.extension.telemetry.SpanWriter;

/**
 * Everything the hot path needs from one published configuration, prepared once: the snapshot, the
 * static attributes as one {@link Attributes} object and the typed keys of every dynamic attribute.
 * Immutable; replaced as a whole on reload.
 */
record EngineState(
    Snapshot snapshot, Attributes staticAttributes, Map<String, AttributeKeySet> keys) {

  static final EngineState DISABLED = of(Snapshot.disabled());

  EngineState {
    keys = Map.copyOf(keys);
  }

  static EngineState of(final Snapshot snapshot) {
    final Map<String, AttributeKeySet> keys = new HashMap<>();
    for (final CompiledRule rule : snapshot.rules()) {
      for (final CompiledAttribute attribute : rule.attributes()) {
        keys.put(attribute.key(), AttributeKeySet.of(attribute.key()));
      }
    }
    return new EngineState(
        snapshot, SpanWriter.staticAttributes(snapshot.staticAttributes()), keys);
  }

  boolean enabled() {
    return snapshot.isEnabled();
  }

  long version() {
    return snapshot.version();
  }
}
