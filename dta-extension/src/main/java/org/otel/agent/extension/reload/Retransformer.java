package org.otel.agent.extension.reload;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.otel.agent.core.binding.ClassHierarchy;
import org.otel.agent.core.plan.PlanDiff;
import org.otel.agent.core.plan.PlanEntry;
import org.otel.agent.core.plan.TransformationPlan;
import org.otel.agent.extension.diag.AgentLog;

/**
 * Applies a plan change to classes that are already loaded (decision D7).
 *
 * <p>It scans the loaded classes for those whose hierarchy contains an affected class name and asks
 * the JVM to retransform them; Byte Buddy then weaves (or leaves out) the advice according to the
 * new {@code ActivePlan}. No list of "touched" classes is kept, so nothing can leak.
 *
 * <p>Without {@link Instrumentation} (degraded mode) nothing is retransformed: advice of removed
 * rules finds no rule and does nothing; added selectors apply to classes loaded later.
 */
public final class Retransformer {

  private final Optional<Instrumentation> instrumentation;
  private final AgentLog log;

  public Retransformer(final Optional<Instrumentation> instrumentation, final AgentLog log) {
    this.instrumentation = instrumentation;
    this.log = log;
  }

  /** Retransforms the loaded classes affected by {@code diff}; returns how many. */
  public int apply(final PlanDiff diff) {
    if (diff.isEmpty()) {
      return 0;
    }
    if (instrumentation.isEmpty()) {
      log.warnOnce(
          "no-instrumentation",
          "the JVM Instrumentation is not available: classes that are already loaded keep their"
              + " current instrumentation until restart; rules removed from the configuration are"
              + " inactive");
      return 0;
    }
    final Instrumentation jvm = instrumentation.get();
    final Set<String> affected = diff.affectedClassNames();
    final List<Class<?>> targets = new ArrayList<>();
    for (final Class<?> type : jvm.getAllLoadedClasses()) {
      if (type.getClassLoader() != null
          && jvm.isModifiableClass(type)
          && hierarchyContainsAny(type, affected)) {
        targets.add(type);
      }
    }
    retransform(jvm, targets);
    return targets.size();
  }

  /**
   * Problems with selectors that only show once the application has loaded its classes: a class
   * that is not loaded (yet) — possibly a typo — or a loaded class without a public method of the
   * selected name. Each entry is one human-readable message, keyed by its selector.
   */
  public Map<String, String> selectorProblems(final TransformationPlan plan) {
    if (instrumentation.isEmpty() || plan.isEmpty()) {
      return Map.of();
    }
    return selectorProblems(plan, instrumentation.get().getAllLoadedClasses());
  }

  static Map<String, String> selectorProblems(
      final TransformationPlan plan, final Class<?>[] loadedClasses) {
    final Map<String, List<Class<?>>> loadedByName = new HashMap<>();
    for (final Class<?> type : loadedClasses) {
      loadedByName.computeIfAbsent(type.getName(), name -> new ArrayList<>()).add(type);
    }
    final Map<String, String> problems = new LinkedHashMap<>();
    for (final PlanEntry entry : plan.entries()) {
      final String className = entry.selector().className();
      final String methodName = entry.selector().methodName();
      final List<Class<?>> candidates = loadedByName.get(className);
      if (candidates == null) {
        problems.put(
            "not-loaded|" + className,
            "selected class "
                + className
                + " is not found or not loaded yet; its rules apply when it loads");
      } else if (candidates.stream().noneMatch(type -> hasPublicMethod(type, methodName))) {
        problems.put(
            "no-method|" + entry.selector(),
            methodName
                + " is not a public method of "
                + className
                + ": the rule for "
                + entry.selector()
                + " never applies");
      }
    }
    return problems;
  }

  private static boolean hasPublicMethod(final Class<?> type, final String name) {
    try {
      return ClassHierarchy.hasPublicMethod(type, name);
    } catch (RuntimeException | LinkageError unresolvable) {
      return true; // cannot inspect the class: do not warn about it
    }
  }

  private void retransform(final Instrumentation jvm, final List<Class<?>> targets) {
    if (targets.isEmpty()) {
      return;
    }
    try {
      jvm.retransformClasses(targets.toArray(Class<?>[]::new));
    } catch (Exception | LinkageError batchFailure) {
      // Isolate the class that fails so the others still get the new plan.
      for (final Class<?> type : targets) {
        try {
          jvm.retransformClasses(type);
        } catch (Exception | LinkageError failure) {
          log.warn("cannot retransform " + type.getName() + ": " + failure);
        }
      }
    }
  }

  private static boolean hierarchyContainsAny(final Class<?> type, final Set<String> names) {
    return !Collections.disjoint(ClassHierarchy.byName(type).keySet(), names);
  }
}
