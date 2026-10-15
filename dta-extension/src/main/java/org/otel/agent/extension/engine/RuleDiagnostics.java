package org.otel.agent.extension.engine;

import java.util.List;
import org.otel.agent.core.binding.MethodBinding.BoundAttribute;
import org.otel.agent.core.binding.MethodBinding.BoundRule;
import org.otel.agent.core.dsl.Condition;
import org.otel.agent.core.path.ExitFrame;
import org.otel.agent.extension.diag.AgentLog;

/**
 * Debug-mode explanations for runtime cases that are otherwise silent (spec {@code diagnostics}):
 * an attribute without value, a value that cannot be written, a condition that failed. Each is
 * reported once per (rule, attribute) and binding, so a hot method cannot flood the log.
 *
 * <p>Only created when debugging is on; otherwise a rule has none and the hot path pays one {@code
 * null} check. The "reported" flags are plain fields: a race reports twice at worst. Attribute
 * values are never logged, only their type.
 */
final class RuleDiagnostics {

  private final AgentLog log;
  private final String rule;
  private final String[] keys;
  private final String[] paths;
  private final boolean[] noValueReported;
  private final boolean[] unwritableReported;
  private boolean conditionFailureReported;

  private RuleDiagnostics(
      final AgentLog log, final String rule, final String[] keys, final String[] paths) {
    this.log = log;
    this.rule = rule;
    this.keys = keys;
    this.paths = paths;
    this.noValueReported = new boolean[keys.length];
    this.unwritableReported = new boolean[keys.length];
  }

  /** Diagnostics for {@code bound} on {@code where} ({@code Class#method}), or {@code null}. */
  static RuleDiagnostics forRule(final BoundRule bound, final String where, final AgentLog log) {
    if (log == null || !log.isDebugEnabled()) {
      return null;
    }
    final List<BoundAttribute> attributes = bound.attributes();
    final String[] keys = new String[attributes.size()];
    final String[] paths = new String[attributes.size()];
    for (int i = 0; i < keys.length; i++) {
      keys[i] = attributes.get(i).key();
      paths[i] = attributes.get(i).reader().path().toString();
    }
    return new RuleDiagnostics(
        log, bound.rule().definition().describe() + " on " + where, keys, paths);
  }

  /** {@link Condition#evaluate}, reporting the first runtime failure. */
  boolean evaluate(final Condition condition, final ExitFrame frame) {
    if (condition == Condition.ALWAYS_TRUE) {
      return true;
    }
    try {
      return condition.test(frame);
    } catch (RuntimeException | LinkageError failure) {
      if (!conditionFailureReported) {
        conditionFailureReported = true;
        log.debug(
            () ->
                rule
                    + ": condition failed with "
                    + failure.getClass().getName()
                    + " and counts as false");
      }
      return false;
    }
  }

  void noValue(final int attribute) {
    if (!noValueReported[attribute]) {
      noValueReported[attribute] = true;
      log.debug(
          () ->
              rule
                  + ": attribute '"
                  + keys[attribute]
                  + "' ("
                  + paths[attribute]
                  + ") has no value: null on the way, a missing property, an index out of range,"
                  + " a Map or a failing getter");
    }
  }

  void unwritable(final int attribute, final Object value) {
    if (!unwritableReported[attribute]) {
      unwritableReported[attribute] = true;
      final String type = value.getClass().getName();
      log.debug(
          () ->
              rule
                  + ": attribute '"
                  + keys[attribute]
                  + "' ("
                  + paths[attribute]
                  + ") is a "
                  + type
                  + ", which cannot be written (use a property that is a string, number,"
                  + " boolean, enum or a collection of one of those)");
    }
  }
}
