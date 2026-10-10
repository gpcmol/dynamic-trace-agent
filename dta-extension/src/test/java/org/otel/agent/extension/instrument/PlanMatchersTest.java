package org.otel.agent.extension.instrument;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.engine.Fixtures;
import java.util.List;
import java.util.Optional;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.otel.agent.core.config.RuleDefinition.Selector;
import org.otel.agent.core.plan.PlanEntry;
import org.otel.agent.core.plan.TransformationPlan;

class PlanMatchersTest {

  private static final String FIXTURES = Fixtures.class.getName() + "$";

  @AfterEach
  void resetPlan() {
    ActivePlan.set(TransformationPlan.EMPTY);
  }

  private static void plan(final PlanEntry... entries) {
    ActivePlan.set(TransformationPlan.of(List.of(entries)));
  }

  private static PlanEntry entry(final String simpleName, final String method, final int maxArg) {
    return PlanEntry.of(new Selector(FIXTURES + simpleName, method), maxArg);
  }

  private static TypeDescription type(final Class<?> type) {
    return TypeDescription.ForLoadedType.of(type);
  }

  private static MethodDescription method(final Class<?> type, final String name) {
    return type(type).getDeclaredMethods().filter(m -> m.getName().equals(name)).getOnly();
  }

  @Test
  void emptyPlanMatchesNothing() {
    assertFalse(PlanMatchers.types().matches(type(Fixtures.Garage.class)));
    assertFalse(PlanMatchers.methods(false).matches(method(Fixtures.Garage.class, "park")));
  }

  @Test
  void selectedTypeAndMethodMatch() {
    plan(entry("Garage", "park", 0));

    assertTrue(PlanMatchers.types().matches(type(Fixtures.Garage.class)));
    assertTrue(PlanMatchers.methods(false).matches(method(Fixtures.Garage.class, "park")));
    assertFalse(PlanMatchers.methods(true).matches(method(Fixtures.Garage.class, "park")));
    assertFalse(PlanMatchers.methods(false).matches(method(Fixtures.Garage.class, "wash")));
  }

  @Test
  void sameMethodNameOnAnotherClassIsNotInstrumented() {
    plan(entry("Garage", "park", 0), entry("Car", "getBrand", 0));

    assertFalse(PlanMatchers.types().matches(type(Fixtures.Hotel.class)));
    assertFalse(PlanMatchers.methods(false).matches(method(Fixtures.Hotel.class, "park")));
    assertFalse(PlanMatchers.methods(false).matches(method(Fixtures.Car.class, "drive")));
  }

  @Test
  void subclassOfSelectedClassMatches() {
    plan(entry("Car", "drive", 0));

    assertTrue(PlanMatchers.types().matches(type(Fixtures.SportsCar.class)));
    assertTrue(PlanMatchers.methods(false).matches(method(Fixtures.SportsCar.class, "drive")));
  }

  @Test
  void highArgumentUsesAllArgumentsAdvice() {
    plan(entry("Garage", "many", 4));

    assertTrue(PlanMatchers.methods(true).matches(method(Fixtures.Garage.class, "many")));
    assertFalse(PlanMatchers.methods(false).matches(method(Fixtures.Garage.class, "many")));
  }

  @Test
  void anySelectorNeedingAllArgumentsWins() {
    plan(entry("Car", "drive", 0), entry("SportsCar", "drive", 6));

    assertEquals(
        Optional.of(true),
        PlanMatchers.argumentMode(ActivePlan.current(), type(Fixtures.SportsCar.class), "drive"));
    assertEquals(
        Optional.of(false),
        PlanMatchers.argumentMode(ActivePlan.current(), type(Fixtures.Car.class), "drive"));
  }

  @Test
  void constructorsAndNonPublicMethodsAreExcluded() {
    plan(entry("Garage", "<init>", 0));

    final MethodDescription constructor =
        type(Fixtures.Garage.class).getDeclaredMethods().filter(m -> m.isConstructor()).getOnly();
    assertFalse(PlanMatchers.methods(false).matches(constructor));
  }

  @Test
  void planChangeIsSeenWithoutNewMatcher() {
    final var types = PlanMatchers.types();
    plan(entry("Garage", "park", 0));
    assertTrue(types.matches(type(Fixtures.Garage.class)));

    plan(entry("Car", "drive", 0));
    assertFalse(types.matches(type(Fixtures.Garage.class)));
  }
}
