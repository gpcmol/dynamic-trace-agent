package org.otel.agent.extension.engine;

import io.opentelemetry.api.trace.Span;
import java.util.List;
import org.otel.agent.core.binding.MethodBinding;
import org.otel.agent.core.binding.MethodBinding.BoundAttribute;
import org.otel.agent.core.binding.MethodBinding.BoundRule;
import org.otel.agent.core.dsl.Condition;
import org.otel.agent.core.path.ExitFrame;
import org.otel.agent.core.path.PathReader;
import org.otel.agent.core.value.AttributeValues;
import org.otel.agent.extension.telemetry.AttributeKeySet;
import org.otel.agent.extension.telemetry.SpanWriter;

/**
 * What to do when one instrumented method of one runtime class exits: its rules, each with a
 * condition and attribute writers whose keys are already resolved. Built once per (class, method,
 * configuration) and kept in that class's binding entry.
 */
final class MethodEnrichment {

  static final MethodEnrichment NONE = new MethodEnrichment(new RuleRunner[0]);

  private final RuleRunner[] rules;

  private MethodEnrichment(final RuleRunner[] rules) {
    this.rules = rules;
  }

  static MethodEnrichment of(final MethodBinding binding, final EngineState state) {
    if (binding.isEmpty()) {
      return NONE;
    }
    final List<BoundRule> bound = binding.rules();
    final RuleRunner[] runners = new RuleRunner[bound.size()];
    for (int i = 0; i < runners.length; i++) {
      runners[i] = RuleRunner.of(bound.get(i), state);
    }
    return new MethodEnrichment(runners);
  }

  boolean isEmpty() {
    return rules.length == 0;
  }

  /**
   * Evaluates every rule's condition; for the rules that hold, writes the static attributes once
   * and each rule's dynamic attributes on the current span — or on one fallback span when there is
   * no valid current span. Nothing is touched when no condition holds.
   */
  void enrich(final ExitFrame frame, final EngineState state, final SpanWriter writer) {
    Span span = null;
    boolean fallback = false;
    try {
      for (final RuleRunner rule : rules) {
        if (!Condition.evaluate(rule.condition, frame)) {
          continue;
        }
        if (span == null) {
          span = Span.current();
          if (!span.getSpanContext().isValid()) {
            span = writer.startFallbackSpan();
            fallback = true;
          }
          span.setAllAttributes(state.staticAttributes());
        }
        rule.writeAttributes(span, frame, writer);
      }
    } finally {
      if (fallback) {
        span.end();
      }
    }
  }

  /** One bound rule with its attribute writers. */
  private static final class RuleRunner {
    private final Condition condition;
    private final PathReader[] readers;
    private final AttributeKeySet[] keys;

    private RuleRunner(
        final Condition condition, final PathReader[] readers, final AttributeKeySet[] keys) {
      this.condition = condition;
      this.readers = readers;
      this.keys = keys;
    }

    static RuleRunner of(final BoundRule rule, final EngineState state) {
      final List<BoundAttribute> attributes = rule.attributes();
      final PathReader[] readers = new PathReader[attributes.size()];
      final AttributeKeySet[] keys = new AttributeKeySet[attributes.size()];
      for (int i = 0; i < readers.length; i++) {
        readers[i] = attributes.get(i).reader();
        final String key = attributes.get(i).key();
        final AttributeKeySet prepared = state.keys().get(key);
        keys[i] = prepared != null ? prepared : AttributeKeySet.of(key);
      }
      return new RuleRunner(rule.condition(), readers, keys);
    }

    /** Each attribute is isolated: one failing getter never blocks the others. */
    void writeAttributes(final Span span, final ExitFrame frame, final SpanWriter writer) {
      for (int i = 0; i < readers.length; i++) {
        try {
          writer.write(span, keys[i], AttributeValues.convert(readers[i].read(frame)));
        } catch (RuntimeException | LinkageError failure) {
          // Enrichment must never affect the application; the attribute is skipped.
        }
      }
    }
  }
}
