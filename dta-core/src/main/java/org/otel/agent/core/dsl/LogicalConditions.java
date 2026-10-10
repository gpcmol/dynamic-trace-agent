package org.otel.agent.core.dsl;

import org.otel.agent.core.path.ExitFrame;

/** {@code and}, {@code or}, {@code not} — both binary operators short-circuit. */
final class LogicalConditions {

  private LogicalConditions() {}

  static final class All implements Condition {
    private final Condition[] terms;

    All(final Condition[] terms) {
      this.terms = terms.clone();
    }

    @Override
    public boolean test(final ExitFrame frame) {
      for (final Condition term : terms) {
        if (!term.test(frame)) {
          return false;
        }
      }
      return true;
    }
  }

  static final class Any implements Condition {
    private final Condition[] terms;

    Any(final Condition[] terms) {
      this.terms = terms.clone();
    }

    @Override
    public boolean test(final ExitFrame frame) {
      for (final Condition term : terms) {
        if (term.test(frame)) {
          return true;
        }
      }
      return false;
    }
  }

  record Negation(Condition term) implements Condition {
    @Override
    public boolean test(final ExitFrame frame) {
      return !term.test(frame);
    }
  }
}
