package org.otel.agent.core.config;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * One {@code <rule>} as written in the configuration (paths and condition not compiled yet).
 *
 * @param position 1-based position of the rule in {@code <dynamic>}, used in messages
 * @param selectors OR alternatives; at least one
 * @param when the condition text, empty when absent (= unconditional)
 * @param attributes dynamic attributes; may be empty (a marker-only rule)
 */
public record RuleDefinition(
    int position,
    List<Selector> selectors,
    Optional<String> when,
    List<DynamicAttribute> attributes) {

  public RuleDefinition {
    if (position < 1) {
      throw new IllegalArgumentException("position must be >= 1");
    }
    selectors = List.copyOf(selectors);
    if (selectors.isEmpty()) {
      throw new IllegalArgumentException("a rule needs at least one selector");
    }
    Objects.requireNonNull(when, "when");
    attributes = List.copyOf(attributes);
  }

  /** Human-readable identity for log messages, e.g. {@code rule #1 (com.example.Garage#park)}. */
  public String describe() {
    return selectors.stream()
        .map(Selector::toString)
        .collect(Collectors.joining(", ", "rule #" + position + " (", ")"));
  }

  /** {@code <selector><class name="…"/><method name="…"/></selector>}. */
  public record Selector(String className, String methodName) {
    public Selector {
      Objects.requireNonNull(className, "className");
      Objects.requireNonNull(methodName, "methodName");
    }

    @Override
    public String toString() {
      return className + '#' + methodName;
    }
  }

  /** {@code <attribute key="…" path="…"/>} inside a rule. */
  public record DynamicAttribute(String key, String path) {
    public DynamicAttribute {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(path, "path");
    }
  }
}
