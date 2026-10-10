package org.otel.agent.core.snapshot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.otel.agent.core.config.ConfigDocument.IgnoredRule;
import org.otel.agent.core.config.ConfigDocument.StaticAttribute;
import org.otel.agent.core.config.RuleDefinition.Selector;
import org.otel.agent.core.plan.TransformationPlan;

/**
 * One complete, immutable configuration as the engine uses it. Published atomically; a method exit
 * sees one snapshot from start to end.
 *
 * <p>Contains no {@code Class} references (see {@code docs/03-classloaders.md} §7), so holding it
 * in a long-lived field can never keep an application classloader alive.
 */
public final class Snapshot {

  private static final Snapshot DISABLED =
      new Snapshot(0, TransformationPlan.EMPTY, List.of(), List.of(), List.of());

  private final long version;
  private final TransformationPlan plan;
  private final List<StaticAttribute> staticAttributes;
  private final List<CompiledRule> rules;
  private final List<IgnoredRule> ignoredRules;
  private final Map<String, List<SelectedRule>> rulesByMethodName;

  Snapshot(
      final long version,
      final TransformationPlan plan,
      final List<StaticAttribute> staticAttributes,
      final List<CompiledRule> rules,
      final List<IgnoredRule> ignoredRules) {
    this.version = version;
    this.plan = Objects.requireNonNull(plan, "plan");
    this.staticAttributes = List.copyOf(staticAttributes);
    this.rules = List.copyOf(rules);
    this.ignoredRules = List.copyOf(ignoredRules);
    this.rulesByMethodName = indexByMethodName(this.rules);
  }

  /** The state before any valid configuration was loaded, or after an explicit disable. */
  public static Snapshot disabled() {
    return DISABLED;
  }

  /** Increases with every published configuration; used to detect stale class bindings. */
  public long version() {
    return version;
  }

  public TransformationPlan plan() {
    return plan;
  }

  /** {@code true} when at least one rule can enrich. */
  public boolean isEnabled() {
    return !rules.isEmpty();
  }

  public List<StaticAttribute> staticAttributes() {
    return staticAttributes;
  }

  public List<CompiledRule> rules() {
    return rules;
  }

  /** Rules disabled at compile time, each with its reason; reported once per load. */
  public List<IgnoredRule> ignoredRules() {
    return ignoredRules;
  }

  /** Selectors (with their rule) whose method name is {@code methodName}, in document order. */
  public List<SelectedRule> selectedRules(final String methodName) {
    return rulesByMethodName.getOrDefault(methodName, List.of());
  }

  /** A selector together with the rule it belongs to. */
  public record SelectedRule(Selector selector, CompiledRule rule) {}

  private static Map<String, List<SelectedRule>> indexByMethodName(final List<CompiledRule> rules) {
    final Map<String, List<SelectedRule>> index = new HashMap<>();
    for (final CompiledRule rule : rules) {
      for (final Selector selector : rule.definition().selectors()) {
        index
            .computeIfAbsent(selector.methodName(), name -> new ArrayList<>())
            .add(new SelectedRule(selector, rule));
      }
    }
    index.replaceAll((name, selected) -> List.copyOf(selected));
    return Map.copyOf(index);
  }
}
