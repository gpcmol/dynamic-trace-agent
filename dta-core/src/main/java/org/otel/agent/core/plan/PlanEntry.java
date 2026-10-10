package org.otel.agent.core.plan;

import java.util.Comparator;
import java.util.Objects;
import org.otel.agent.core.config.RuleDefinition.Selector;

/**
 * One instrumented selector and how its advice passes arguments.
 *
 * @param selector the selected class (or interface) and method name
 * @param allArguments {@code true} when a rule on this selector reads {@code $arg4} or higher, so
 *     the advice must pass all arguments as an array instead of the first four individually
 */
public record PlanEntry(Selector selector, boolean allArguments) {

  /** Number of arguments the fixed-arity advice passes individually ({@code $arg0..$arg3}). */
  public static final int FIXED_ARGUMENTS = 4;

  static final Comparator<PlanEntry> ORDER =
      Comparator.comparing((PlanEntry entry) -> entry.selector().className())
          .thenComparing(entry -> entry.selector().methodName())
          .thenComparing(PlanEntry::allArguments);

  public PlanEntry {
    Objects.requireNonNull(selector, "selector");
  }

  /** The entry for a selector whose rules read arguments up to {@code maxArgumentIndex}. */
  public static PlanEntry of(final Selector selector, final int maxArgumentIndex) {
    return new PlanEntry(selector, maxArgumentIndex >= FIXED_ARGUMENTS);
  }

  @Override
  public String toString() {
    return selector + (allArguments ? " (all arguments)" : "");
  }
}
