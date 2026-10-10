package org.otel.agent.core.dsl;

import org.otel.agent.core.path.ExitFrame;

/**
 * An executable, typed condition bound to one class. Created by {@link ConditionTyper}; evaluated
 * on the hot path with {@link #evaluate(Condition, ExitFrame)}.
 */
public interface Condition {

  /** Condition of a rule without {@code <when>} or with {@code <when>true</when>}. */
  Condition ALWAYS_TRUE = frame -> true;

  boolean test(ExitFrame frame);

  /**
   * Evaluates without ever throwing: any runtime failure counts as {@code false}, so the rule is
   * skipped for this call and the application is unaffected.
   */
  static boolean evaluate(final Condition condition, final ExitFrame frame) {
    if (condition == ALWAYS_TRUE) {
      return true;
    }
    try {
      return condition.test(frame);
    } catch (RuntimeException | LinkageError failure) {
      return false;
    }
  }
}
