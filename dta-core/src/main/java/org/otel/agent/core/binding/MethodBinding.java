package org.otel.agent.core.binding;

import java.util.List;
import java.util.Objects;
import org.otel.agent.core.config.ConfigDocument.IgnoredRule;
import org.otel.agent.core.dsl.Condition;
import org.otel.agent.core.path.PathReader;
import org.otel.agent.core.snapshot.CompiledRule;

/**
 * The rules that apply when one instrumented method of one runtime class exits, ready to run.
 *
 * <p>Owned by that class's binding (in its {@code ClassValue} entry); holds readers for that class
 * and therefore must never be stored anywhere long-lived. See {@code docs/03-classloaders.md} §7.
 *
 * @param rules rules to evaluate, in document order; each has its own condition
 * @param problems rules that were ignored for this class, with the reason (warn once)
 */
public record MethodBinding(List<BoundRule> rules, List<IgnoredRule> problems) {

  public static final MethodBinding NONE = new MethodBinding(List.of(), List.of());

  public MethodBinding {
    rules = List.copyOf(rules);
    problems = List.copyOf(problems);
  }

  public boolean isEmpty() {
    return rules.isEmpty();
  }

  /** A rule bound to a class: typed condition and attribute readers. */
  public record BoundRule(CompiledRule rule, Condition condition, List<BoundAttribute> attributes) {
    public BoundRule {
      Objects.requireNonNull(rule, "rule");
      Objects.requireNonNull(condition, "condition");
      attributes = List.copyOf(attributes);
    }
  }

  /** A dynamic attribute: key and the reader of its path. */
  public record BoundAttribute(String key, PathReader reader) {
    public BoundAttribute {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(reader, "reader");
    }
  }
}
