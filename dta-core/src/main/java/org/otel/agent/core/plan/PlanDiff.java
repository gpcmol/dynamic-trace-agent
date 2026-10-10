package org.otel.agent.core.plan;

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;

/**
 * The difference between two plans. Classes whose names appear in {@link #affectedClassNames()} (or
 * that extend / implement them) must be retransformed: added entries get advice, removed entries
 * lose it, and an entry whose argument mode changed is in both sets.
 */
public record PlanDiff(Set<PlanEntry> added, Set<PlanEntry> removed) {

  public PlanDiff {
    added = Set.copyOf(added);
    removed = Set.copyOf(removed);
  }

  public static PlanDiff between(final TransformationPlan before, final TransformationPlan after) {
    final Set<PlanEntry> added = new HashSet<>(after.entries());
    added.removeAll(before.entries());
    final Set<PlanEntry> removed = new HashSet<>(before.entries());
    removed.removeAll(after.entries());
    return new PlanDiff(added, removed);
  }

  /** {@code true} when no class needs retransformation. */
  public boolean isEmpty() {
    return added.isEmpty() && removed.isEmpty();
  }

  /** Selected class names whose advice changes, sorted. */
  public Set<String> affectedClassNames() {
    final Set<String> names = new TreeSet<>();
    added.forEach(entry -> names.add(entry.selector().className()));
    removed.forEach(entry -> names.add(entry.selector().className()));
    return names;
  }
}
