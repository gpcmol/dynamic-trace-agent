package org.otel.agent.core.plan;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which methods carry advice, expressed in class and method <em>names</em> only — so it can be
 * built before application classes exist and never pins a classloader. The instrumentation matchers
 * read it; reload compares two plans with {@link PlanDiff} to decide what to retransform.
 *
 * <p>Two plans with equal entries are equal, whatever the {@code <when>} or attribute contents of
 * their rules: changing those never requires retransformation.
 */
public final class TransformationPlan {

  public static final TransformationPlan EMPTY = new TransformationPlan(List.of());

  private final Set<PlanEntry> entries;
  private final Map<String, Map<String, PlanEntry>> byClassAndMethod;

  private TransformationPlan(final Collection<PlanEntry> entries) {
    final TreeSet<PlanEntry> sorted = new TreeSet<>(PlanEntry.ORDER);
    sorted.addAll(entries);
    this.entries = Collections.unmodifiableSet(sorted);
    final Map<String, Map<String, PlanEntry>> index = new HashMap<>();
    for (final PlanEntry entry : sorted) {
      index
          .computeIfAbsent(entry.selector().className(), name -> new HashMap<>())
          .put(entry.selector().methodName(), entry);
    }
    index.replaceAll((className, methods) -> Map.copyOf(methods));
    this.byClassAndMethod = Map.copyOf(index);
  }

  public static TransformationPlan of(final Collection<PlanEntry> entries) {
    return entries.isEmpty() ? EMPTY : new TransformationPlan(entries);
  }

  public boolean isEmpty() {
    return entries.isEmpty();
  }

  /** All entries, sorted by class name, method name. */
  public Set<PlanEntry> entries() {
    return entries;
  }

  /** Names of all selected classes and interfaces. */
  public Set<String> classNames() {
    return byClassAndMethod.keySet();
  }

  /** The entry for a selected class name and method name, if selected. */
  public Optional<PlanEntry> entry(final String className, final String methodName) {
    final Map<String, PlanEntry> methods = byClassAndMethod.get(className);
    return methods == null ? Optional.empty() : Optional.ofNullable(methods.get(methodName));
  }

  /** A stable, human-readable fingerprint of the instrumented footprint. */
  public String fingerprint() {
    return entries.toString();
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof TransformationPlan plan && entries.equals(plan.entries);
  }

  @Override
  public int hashCode() {
    return entries.hashCode();
  }

  @Override
  public String toString() {
    return "TransformationPlan" + entries;
  }
}
