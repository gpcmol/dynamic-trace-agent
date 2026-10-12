package org.otel.agent.core.config;

import java.util.List;
import java.util.Objects;

/**
 * A syntactically valid configuration document.
 *
 * @param staticAttributes attributes written on every enrichment event of every rule
 * @param rules the rules that passed all document-level checks
 * @param ignoredRules rules that are valid XML but disabled, each with the reason (reported once)
 */
public record ConfigDocument(
    List<StaticAttribute> staticAttributes,
    List<RuleDefinition> rules,
    List<IgnoredRule> ignoredRules) {

  public ConfigDocument {
    staticAttributes = List.copyOf(staticAttributes);
    rules = List.copyOf(rules);
    ignoredRules = List.copyOf(ignoredRules);
  }

  /** A static attribute: {@code <static><attribute key="…" value="…"/></static>}. */
  public record StaticAttribute(String key, String value) {
    public StaticAttribute {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(value, "value");
    }
  }

  /** A rule that is disabled, with the reason, e.g. a selector on a protected namespace. */
  public record IgnoredRule(RuleDefinition rule, String reason) {
    public IgnoredRule {
      Objects.requireNonNull(rule, "rule");
      Objects.requireNonNull(reason, "reason");
    }
  }
}
