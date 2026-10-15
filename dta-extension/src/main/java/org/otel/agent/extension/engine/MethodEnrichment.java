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
import org.otel.agent.extension.diag.AgentLog;
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

  /**
   * @param where {@code Class#method}, for debug messages
   * @param log explains silent runtime cases when debugging is on (see {@link RuleDiagnostics})
   */
  static MethodEnrichment of(
      final MethodBinding binding,
      final EngineState state,
      final String where,
      final AgentLog log) {
    if (binding.isEmpty()) {
      return NONE;
    }
    final List<BoundRule> bound = binding.rules();
    final RuleRunner[] runners = new RuleRunner[bound.size()];
    for (int i = 0; i < runners.length; i++) {
      runners[i] =
          RuleRunner.of(bound.get(i), state, RuleDiagnostics.forRule(bound.get(i), where, log));
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
        if (!rule.holds(frame)) {
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
  private static class RuleRunner {
    final Condition condition;
    final PathReader[] readers;
    final AttributeKeySet[] keys;

    RuleRunner(
        final Condition condition, final PathReader[] readers, final AttributeKeySet[] keys) {
      this.condition = condition;
      this.readers = readers;
      this.keys = keys;
    }

    /**
     * The runner for {@code rule}; with {@code diagnostics} an {@link ExplainedRuleRunner}. That
     * subclass is only loaded in debug mode, so otherwise these methods stay monomorphic.
     */
    static RuleRunner of(
        final BoundRule rule, final EngineState state, final RuleDiagnostics diagnostics) {
      final List<BoundAttribute> attributes = rule.attributes();
      final PathReader[] readers = new PathReader[attributes.size()];
      final AttributeKeySet[] keys = new AttributeKeySet[attributes.size()];
      for (int i = 0; i < readers.length; i++) {
        readers[i] = attributes.get(i).reader();
        final String key = attributes.get(i).key();
        final AttributeKeySet prepared = state.keys().get(key);
        keys[i] = prepared != null ? prepared : AttributeKeySet.of(key);
      }
      return diagnostics == null
          ? new RuleRunner(rule.condition(), readers, keys)
          : new ExplainedRuleRunner(rule.condition(), readers, keys, diagnostics);
    }

    boolean holds(final ExitFrame frame) {
      return Condition.evaluate(condition, frame);
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

  /** Debug mode: as {@link RuleRunner}, but says once why a condition or attribute failed. */
  private static final class ExplainedRuleRunner extends RuleRunner {
    private final RuleDiagnostics diagnostics;

    ExplainedRuleRunner(
        final Condition condition,
        final PathReader[] readers,
        final AttributeKeySet[] keys,
        final RuleDiagnostics diagnostics) {
      super(condition, readers, keys);
      this.diagnostics = diagnostics;
    }

    @Override
    boolean holds(final ExitFrame frame) {
      return diagnostics.evaluate(condition, frame);
    }

    @Override
    void writeAttributes(final Span span, final ExitFrame frame, final SpanWriter writer) {
      for (int i = 0; i < readers.length; i++) {
        try {
          write(span, frame, writer, i);
        } catch (RuntimeException | LinkageError failure) {
          // As in RuleRunner: the attribute is skipped.
        }
      }
    }

    private void write(
        final Span span, final ExitFrame frame, final SpanWriter writer, final int attribute) {
      final Object value = readers[attribute].read(frame);
      if (value == null) {
        diagnostics.noValue(attribute);
        return;
      }
      final Object converted = AttributeValues.convert(value);
      if (converted == null) {
        diagnostics.unwritable(attribute, value);
        return;
      }
      writer.write(span, keys[attribute], converted);
    }
  }
}
