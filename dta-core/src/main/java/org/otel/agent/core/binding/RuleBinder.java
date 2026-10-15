package org.otel.agent.core.binding;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.otel.agent.core.binding.MethodBinding.BoundAttribute;
import org.otel.agent.core.binding.MethodBinding.BoundRule;
import org.otel.agent.core.config.ConfigDocument.IgnoredRule;
import org.otel.agent.core.config.RuleDefinition.Selector;
import org.otel.agent.core.dsl.Condition;
import org.otel.agent.core.dsl.ConditionException;
import org.otel.agent.core.dsl.ConditionTyper;
import org.otel.agent.core.dsl.ExitSignature;
import org.otel.agent.core.path.CompiledPath;
import org.otel.agent.core.path.GenericTypes;
import org.otel.agent.core.path.PathReader;
import org.otel.agent.core.path.PathRoot;
import org.otel.agent.core.path.PathSegment;
import org.otel.agent.core.path.PropertyAccessors;
import org.otel.agent.core.snapshot.CompiledRule;
import org.otel.agent.core.snapshot.CompiledRule.CompiledAttribute;
import org.otel.agent.core.snapshot.Snapshot;
import org.otel.agent.core.snapshot.Snapshot.SelectedRule;

/**
 * Binds the rules of a {@link Snapshot} to one instrumented method of one runtime class. Runs once
 * per (class, method, snapshot), on the first exit after a class loads or a configuration changes.
 *
 * <p>A selector applies when its class is the runtime class or one of its superclasses or
 * interfaces, and its method name matches. Every applicable rule is bound with its own condition. A
 * rule that refers to something that does not exist (method, property) or whose condition does not
 * type-check is ignored for this class and reported in {@link MethodBinding#problems()}.
 */
public final class RuleBinder {

  private RuleBinder() {}

  /**
   * Binds the snapshot's rules for one method.
   *
   * @param receiver the runtime class of {@code $this}
   * @param method the instrumented method as declared in {@code receiver}
   */
  public static MethodBinding bind(
      final Snapshot snapshot, final Class<?> receiver, final Method method) {
    final List<SelectedRule> candidates = snapshot.selectedRules(method.getName());
    if (candidates.isEmpty()) {
      return MethodBinding.NONE;
    }
    final Map<String, Class<?>> hierarchy = ClassHierarchy.byName(receiver);
    final ExitSignature signature = signature(receiver, method);
    final List<BoundRule> rules = new ArrayList<>();
    final List<IgnoredRule> problems = new ArrayList<>();
    for (final SelectedRule candidate : candidates) {
      final Class<?> selectedType = hierarchy.get(candidate.selector().className());
      if (selectedType == null) {
        continue;
      }
      try {
        rules.add(bindRule(candidate, selectedType, signature, receiver));
      } catch (BindingException problem) {
        problems.add(new IgnoredRule(candidate.rule().definition(), problem.getMessage()));
      }
    }
    return new MethodBinding(rules, problems);
  }

  private static BoundRule bindRule(
      final SelectedRule candidate,
      final Class<?> selectedType,
      final ExitSignature signature,
      final Class<?> receiver)
      throws BindingException {
    final Selector selector = candidate.selector();
    if (!ClassHierarchy.hasPublicMethod(selectedType, selector.methodName())) {
      throw new BindingException(
          selector.methodName() + " is not a public method of " + selectedType.getName());
    }
    final CompiledRule rule = candidate.rule();
    final List<BoundAttribute> attributes = new ArrayList<>();
    for (final CompiledAttribute attribute : rule.attributes()) {
      checkAttributePath(attribute, signature);
      attributes.add(
          new BoundAttribute(attribute.key(), PathReader.ownedBy(attribute.path(), receiver)));
    }
    return new BoundRule(rule, typedCondition(rule, signature, receiver), attributes);
  }

  private static Condition typedCondition(
      final CompiledRule rule, final ExitSignature signature, final Class<?> receiver)
      throws BindingException {
    if (rule.condition().isEmpty()) {
      return Condition.ALWAYS_TRUE;
    }
    try {
      return ConditionTyper.type(rule.condition().get(), signature, receiver);
    } catch (ConditionException invalid) {
      throw new BindingException("condition: " + invalid.getMessage());
    }
  }

  /**
   * The static types of {@code method} on {@code receiver}, with generic types resolved against the
   * receiver ({@code List<T>} declared in {@code Base<T>} is {@code List<Tree>} on {@code Forest
   * extends Base<Tree>}).
   */
  private static ExitSignature signature(final Class<?> receiver, final Method method) {
    final List<Class<?>> parameterTypes = List.of(method.getParameterTypes());
    final Type[] generic = method.getGenericParameterTypes();
    if (generic.length != parameterTypes.size()) {
      return new ExitSignature(receiver, parameterTypes, method.getReturnType());
    }
    final List<Type> genericParameterTypes = new ArrayList<>(generic.length);
    for (final Type parameter : generic) {
      genericParameterTypes.add(
          GenericTypes.resolve(parameter, method.getDeclaringClass(), receiver));
    }
    return new ExitSignature(
        receiver,
        parameterTypes,
        method.getReturnType(),
        genericParameterTypes,
        GenericTypes.resolve(method.getGenericReturnType(), method.getDeclaringClass(), receiver));
  }

  /**
   * Reports a property that cannot exist: one missing on a <em>final</em> static type. For
   * non-final types a subclass may provide it, so the path is resolved at runtime (missing gives no
   * attribute). Collections, arrays, maps and {@code Object} end the static check.
   */
  private static void checkAttributePath(
      final CompiledAttribute attribute, final ExitSignature signature) throws BindingException {
    final CompiledPath path = attribute.path();
    Class<?> type = rootType(path.root(), signature);
    if (path.hasRootIndex() && type != null) {
      type = componentTypeOrNull(type);
    }
    for (final PathSegment segment : path.segments()) {
      if (type == null || !isExactType(type)) {
        return;
      }
      final Optional<Class<?>> propertyType =
          PropertyAccessors.propertyType(type, segment.property());
      if (propertyType.isEmpty()) {
        throw new BindingException(
            "attribute '"
                + attribute.key()
                + "': "
                + PropertyAccessors.describeMissing(type, segment.property())
                + " (in '"
                + path
                + "')");
      }
      type = segment.isIndexed() ? componentTypeOrNull(propertyType.get()) : propertyType.get();
    }
  }

  private static boolean isExactType(final Class<?> type) {
    return Modifier.isFinal(type.getModifiers())
        && !type.isArray()
        && !type.isPrimitive()
        && !Iterable.class.isAssignableFrom(type)
        && !Map.class.isAssignableFrom(type);
  }

  private static Class<?> componentTypeOrNull(final Class<?> type) {
    return type.isArray() ? type.getComponentType() : null;
  }

  private static Class<?> rootType(final PathRoot root, final ExitSignature signature) {
    return switch (root) {
      case PathRoot.This ignored -> signature.self();
      case PathRoot.Return ignored ->
          signature.returnType() == void.class ? null : signature.returnType();
      case PathRoot.Argument argument ->
          argument.index() < signature.parameterTypes().size()
              ? signature.parameterTypes().get(argument.index())
              : null;
    };
  }

  /** A rule cannot be bound to this class. */
  private static final class BindingException extends Exception {
    private static final long serialVersionUID = 1L;

    BindingException(final String message) {
      super(message);
    }
  }
}
