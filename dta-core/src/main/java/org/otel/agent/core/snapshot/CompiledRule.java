package org.otel.agent.core.snapshot;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.otel.agent.core.config.RuleDefinition;
import org.otel.agent.core.dsl.Expression;
import org.otel.agent.core.path.CompiledPath;

/**
 * A rule with its paths compiled and its condition parsed (not yet typed). Pure data: no {@code
 * Class} references.
 *
 * @param definition the rule as written, for its selectors and messages
 * @param condition the parsed {@code <when>}, empty when unconditional
 * @param attributes the dynamic attributes with compiled paths
 * @param maxArgumentIndex highest {@code $argN} read by the condition or an attribute, -1 if none
 */
public record CompiledRule(
    RuleDefinition definition,
    Optional<Expression> condition,
    List<CompiledAttribute> attributes,
    int maxArgumentIndex) {

  public CompiledRule {
    Objects.requireNonNull(definition, "definition");
    Objects.requireNonNull(condition, "condition");
    attributes = List.copyOf(attributes);
  }

  /** {@code <attribute key="…" path="…"/>} with its path compiled. */
  public record CompiledAttribute(String key, CompiledPath path) {
    public CompiledAttribute {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(path, "path");
    }
  }
}
