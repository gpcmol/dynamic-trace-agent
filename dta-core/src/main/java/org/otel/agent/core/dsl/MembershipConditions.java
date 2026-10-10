package org.otel.agent.core.dsl;

import java.util.Arrays;
import java.util.Set;
import org.otel.agent.core.path.ExitFrame;

/**
 * {@code value in [a, b, …]}. Candidates are prepared once (sorted primitive arrays, a set of
 * strings), so a test does not allocate. Numeric values are compared after normalisation: an {@code
 * int} property matches a long literal.
 */
final class MembershipConditions {

  private MembershipConditions() {}

  static final class InLongs implements Condition {
    private final ValueNode value;
    private final long[] sortedCandidates;

    InLongs(final ValueNode value, final long[] candidates) {
      this.value = value;
      this.sortedCandidates = candidates.clone();
      Arrays.sort(sortedCandidates);
    }

    @Override
    public boolean test(final ExitFrame frame) {
      return value.value(frame) instanceof Number number
          && Arrays.binarySearch(sortedCandidates, number.longValue()) >= 0;
    }
  }

  static final class InDoubles implements Condition {
    private final ValueNode value;
    private final double[] sortedCandidates;

    InDoubles(final ValueNode value, final double[] candidates) {
      this.value = value;
      this.sortedCandidates = candidates.clone();
      Arrays.sort(sortedCandidates);
    }

    @Override
    public boolean test(final ExitFrame frame) {
      return value.value(frame) instanceof Number number
          && Arrays.binarySearch(sortedCandidates, number.doubleValue()) >= 0;
    }
  }

  record InStrings(ValueNode value, Set<String> candidates) implements Condition {
    InStrings {
      candidates = Set.copyOf(candidates);
    }

    @Override
    public boolean test(final ExitFrame frame) {
      return value.value(frame) instanceof String text && candidates.contains(text);
    }
  }

  record InBooleans(ValueNode value, boolean acceptsTrue, boolean acceptsFalse)
      implements Condition {
    @Override
    public boolean test(final ExitFrame frame) {
      return value.value(frame) instanceof Boolean flag && (flag ? acceptsTrue : acceptsFalse);
    }
  }
}
