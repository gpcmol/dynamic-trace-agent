package org.otel.agent.core.snapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.otel.agent.core.config.ConfigDocument;
import org.otel.agent.core.config.ConfigDocument.IgnoredRule;
import org.otel.agent.core.config.ConfigurationException;
import org.otel.agent.core.config.RuleDefinition;
import org.otel.agent.core.config.RuleDefinition.DynamicAttribute;
import org.otel.agent.core.config.RuleDefinition.Selector;
import org.otel.agent.core.dsl.ConditionException;
import org.otel.agent.core.dsl.Expression;
import org.otel.agent.core.dsl.ExpressionParser;
import org.otel.agent.core.dsl.Operand;
import org.otel.agent.core.path.CompiledPath;
import org.otel.agent.core.path.InvalidPathException;
import org.otel.agent.core.path.PathCompiler;
import org.otel.agent.core.path.PathRoot;
import org.otel.agent.core.plan.PlanEntry;
import org.otel.agent.core.plan.TransformationPlan;

/**
 * Compiles a {@link ConfigDocument} into a {@link Snapshot}: compiles attribute paths, parses
 * conditions and derives the {@link TransformationPlan}.
 *
 * <ul>
 *   <li>An invalid attribute path rejects the whole document ({@link ConfigurationException}).
 *   <li>A condition with a syntax error disables only its rule (an {@link IgnoredRule}).
 * </ul>
 */
public final class SnapshotCompiler {

  private SnapshotCompiler() {}

  public static Snapshot compile(final ConfigDocument document, final long version)
      throws ConfigurationException {
    final List<CompiledRule> rules = new ArrayList<>();
    final List<IgnoredRule> ignored = new ArrayList<>(document.ignoredRules());
    for (final RuleDefinition definition : document.rules()) {
      final List<CompiledRule.CompiledAttribute> attributes = compileAttributes(definition);
      try {
        rules.add(compileRule(definition, attributes));
      } catch (ConditionException invalidCondition) {
        ignored.add(new IgnoredRule(definition, "condition: " + invalidCondition.getMessage()));
      }
    }
    return new Snapshot(version, planOf(rules), document.staticAttributes(), rules, ignored);
  }

  private static List<CompiledRule.CompiledAttribute> compileAttributes(
      final RuleDefinition definition) throws ConfigurationException {
    final List<CompiledRule.CompiledAttribute> attributes = new ArrayList<>();
    for (final DynamicAttribute attribute : definition.attributes()) {
      try {
        attributes.add(
            new CompiledRule.CompiledAttribute(
                attribute.key(), PathCompiler.attributePath(attribute.path())));
      } catch (InvalidPathException invalid) {
        throw new ConfigurationException(
            "rule #"
                + definition.position()
                + ", attribute '"
                + attribute.key()
                + "': "
                + invalid.getMessage(),
            invalid);
      }
    }
    return attributes;
  }

  private static CompiledRule compileRule(
      final RuleDefinition definition, final List<CompiledRule.CompiledAttribute> attributes)
      throws ConditionException {
    final Optional<Expression> condition =
        definition.when().isPresent()
            ? Optional.of(ExpressionParser.parse(definition.when().get()))
            : Optional.empty();
    int maxArgument = -1;
    for (final CompiledRule.CompiledAttribute attribute : attributes) {
      maxArgument = Math.max(maxArgument, argumentIndex(attribute.path()));
    }
    if (condition.isPresent()) {
      maxArgument = Math.max(maxArgument, maxArgumentIndex(condition.get()));
    }
    return new CompiledRule(definition, condition, attributes, maxArgument);
  }

  private static TransformationPlan planOf(final List<CompiledRule> rules) {
    final List<PlanEntry> entries = new ArrayList<>();
    for (final CompiledRule rule : rules) {
      for (final Selector selector : rule.definition().selectors()) {
        entries.add(PlanEntry.of(selector, rule.maxArgumentIndex()));
      }
    }
    return TransformationPlan.of(entries);
  }

  private static int argumentIndex(final CompiledPath path) {
    return path.root() instanceof PathRoot.Argument argument ? argument.index() : -1;
  }

  private static int maxArgumentIndex(final Expression expression) {
    return switch (expression) {
      case Expression.Or or ->
          or.terms().stream().mapToInt(SnapshotCompiler::maxArgumentIndex).max().orElse(-1);
      case Expression.And and ->
          and.terms().stream().mapToInt(SnapshotCompiler::maxArgumentIndex).max().orElse(-1);
      case Expression.Not not -> maxArgumentIndex(not.term());
      case Expression.Comparison comparison ->
          Math.max(maxArgumentIndex(comparison.left()), maxArgumentIndex(comparison.right()));
      case Expression.Membership membership -> maxArgumentIndex(membership.value());
      case Expression.TextFunction function ->
          Math.max(maxArgumentIndex(function.haystack()), maxArgumentIndex(function.needle()));
      case Expression.Truthy truthy -> maxArgumentIndex(truthy.operand());
    };
  }

  private static int maxArgumentIndex(final Operand operand) {
    return switch (operand) {
      case Operand.Literal ignored -> -1;
      case Operand.Reference reference -> argumentIndex(reference.path());
      case Operand.Size size -> maxArgumentIndex(size.operand());
      case Operand.Arithmetic arithmetic ->
          Math.max(maxArgumentIndex(arithmetic.left()), maxArgumentIndex(arithmetic.right()));
    };
  }
}
