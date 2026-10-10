package org.otel.agent.extension.instrument;

import static io.opentelemetry.javaagent.extension.matcher.AgentElementMatchers.hasSuperType;
import static net.bytebuddy.matcher.ElementMatchers.namedOneOf;

import java.util.Optional;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.otel.agent.core.plan.PlanEntry;
import org.otel.agent.core.plan.TransformationPlan;

/**
 * Byte Buddy matchers driven by the {@link ActivePlan}. They are evaluated when a class is loaded
 * or retransformed, never on the hot path.
 *
 * <ul>
 *   <li>A type matches when it, a superclass or an interface is a selected class name.
 *   <li>A method matches when it is a public, concrete instance method whose name is selected for
 *       one of the type's hierarchy names — per (class, method) pair, not by name alone.
 * </ul>
 */
final class PlanMatchers {

  private PlanMatchers() {}

  /** Types that contain a selected method (or inherit a selected declaration). */
  static ElementMatcher.Junction<TypeDescription> types() {
    return new TypeMatcher();
  }

  /**
   * Selected methods whose advice passes arguments individually ({@code allArguments == false}) or
   * as an array ({@code allArguments == true}).
   */
  static ElementMatcher.Junction<MethodDescription> methods(final boolean allArguments) {
    return new MethodMatcher(allArguments);
  }

  /**
   * The argument mode for a method, or empty when it is not selected. When several selectors match
   * (e.g. {@code Car#drive} and {@code SportsCar#drive}) and one needs all arguments, all are
   * passed.
   */
  static Optional<Boolean> argumentMode(
      final TransformationPlan plan, final TypeDescription type, final String methodName) {
    boolean selected = false;
    boolean allArguments = false;
    for (final String name : TypeHierarchy.namesOf(type)) {
      final Optional<PlanEntry> entry = plan.entry(name, methodName);
      if (entry.isPresent()) {
        selected = true;
        allArguments |= entry.get().allArguments();
      }
    }
    return selected ? Optional.of(allArguments) : Optional.empty();
  }

  private static final class TypeMatcher
      extends ElementMatcher.Junction.AbstractBase<TypeDescription> {

    /** Matcher for the last plan seen; rebuilt when the plan object changes. */
    private volatile CachedMatcher cached = new CachedMatcher(TransformationPlan.EMPTY, null);

    @Override
    @SuppressWarnings("ReferenceEquality") // identity: a new plan object means a reload
    public boolean matches(final TypeDescription type) {
      final TransformationPlan plan = ActivePlan.current();
      if (plan.isEmpty()) {
        return false;
      }
      CachedMatcher current = cached;
      if (current.plan != plan) {
        final ElementMatcher.Junction<TypeDescription> selected =
            namedOneOf(plan.classNames().toArray(String[]::new));
        current = new CachedMatcher(plan, selected.or(hasSuperType(selected)));
        cached = current;
      }
      return current.matcher.matches(type);
    }

    private record CachedMatcher(
        TransformationPlan plan, ElementMatcher<TypeDescription> matcher) {}
  }

  private static final class MethodMatcher
      extends ElementMatcher.Junction.AbstractBase<MethodDescription> {
    private final boolean allArguments;

    MethodMatcher(final boolean allArguments) {
      this.allArguments = allArguments;
    }

    @Override
    public boolean matches(final MethodDescription method) {
      if (!method.isMethod()
          || !method.isPublic()
          || method.isStatic()
          || method.isAbstract()
          || method.isNative()
          || method.isBridge()
          || method.isSynthetic()) {
        return false;
      }
      final Optional<Boolean> mode =
          argumentMode(
              ActivePlan.current(),
              method.getDeclaringType().asErasure(),
              method.getInternalName());
      return mode.isPresent() && mode.get() == allArguments;
    }
  }
}
