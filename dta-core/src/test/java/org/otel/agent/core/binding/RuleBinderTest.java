package org.otel.agent.core.binding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.binding.BindingFixtures;
import com.example.binding.BindingFixtures.Car;
import com.example.binding.BindingFixtures.Garage;
import com.example.binding.BindingFixtures.Result;
import com.example.binding.BindingFixtures.SportsCar;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.otel.agent.core.binding.MethodBinding.BoundRule;
import org.otel.agent.core.config.XmlConfigurationParser;
import org.otel.agent.core.dsl.Condition;
import org.otel.agent.core.path.ExitFrame;
import org.otel.agent.core.snapshot.Snapshot;
import org.otel.agent.core.snapshot.SnapshotCompiler;

class RuleBinderTest {

  private static final String PKG = BindingFixtures.class.getName() + "$";

  private static Snapshot snapshot(final String rules) throws Exception {
    return SnapshotCompiler.compile(
        new XmlConfigurationParser()
            .parse("<configuration><dynamic>" + rules + "</dynamic></configuration>"),
        1);
  }

  private static String rule(final String className, final String method, final String body) {
    return "<rule><selector><class name=\""
        + PKG
        + className
        + "\"/><method name=\""
        + method
        + "\"/></selector>"
        + body
        + "</rule>";
  }

  private static Method method(final Class<?> type, final String name) throws Exception {
    for (final Method method : type.getDeclaredMethods()) {
      if (method.getName().equals(name)) {
        return method;
      }
    }
    throw new NoSuchMethodException(name);
  }

  private record Frame(Object self, Object returned, Object argument0) implements ExitFrame {
    @Override
    public Object argument(final int index) {
      return index == 0 ? argument0 : null;
    }
  }

  @Test
  void bindsSelectorOnTheExactClass() throws Exception {
    final MethodBinding binding =
        RuleBinder.bind(
            snapshot(
                rule(
                    "Garage",
                    "park",
                    "<when>$arg0.brand == 'bmw'</when>"
                        + "<attribute key=\"accepted\" path=\"$return.accepted\"/>")),
            Garage.class,
            method(Garage.class, "park"));

    assertEquals(1, binding.rules().size());
    final BoundRule rule = binding.rules().get(0);
    final Frame frame = new Frame(new Garage(), new Result(true), new Car());
    assertTrue(Condition.evaluate(rule.condition(), frame));
    assertEquals(true, rule.attributes().get(0).reader().read(frame));
    assertTrue(binding.problems().isEmpty());
  }

  @Test
  void selectorOnSuperclassAppliesToSubclass() throws Exception {
    final MethodBinding binding =
        RuleBinder.bind(
            snapshot(rule("Car", "drive", "")), SportsCar.class, method(SportsCar.class, "drive"));

    assertEquals(1, binding.rules().size());
  }

  @Test
  void selectorOnInterfaceAppliesToImplementation() throws Exception {
    final MethodBinding binding =
        RuleBinder.bind(
            snapshot(rule("Vehicle", "drive", "")), Car.class, method(Car.class, "drive"));

    assertEquals(1, binding.rules().size());
  }

  @Test
  void selectorOnUnrelatedClassDoesNotApply() throws Exception {
    final MethodBinding binding =
        RuleBinder.bind(
            snapshot(rule("Car", "park", "")), Garage.class, method(Garage.class, "park"));

    assertTrue(binding.isEmpty());
    assertTrue(binding.problems().isEmpty());
  }

  @Test
  void everyMatchingSelectorKeepsItsOwnCondition() throws Exception {
    final MethodBinding binding =
        RuleBinder.bind(
            snapshot(
                rule(
                        "Car",
                        "drive",
                        "<when>$return == 'nope'</when><attribute key=\"a\" path=\"$this.brand\"/>")
                    + rule(
                        "SportsCar",
                        "drive",
                        "<when>$return == 'VROOM'</when><attribute key=\"b\" path=\"$this.brand\"/>")),
            SportsCar.class,
            method(SportsCar.class, "drive"));

    final Frame frame = new Frame(new SportsCar(), "VROOM", null);
    assertEquals(2, binding.rules().size());
    assertEquals(false, Condition.evaluate(binding.rules().get(0).condition(), frame));
    assertEquals(true, Condition.evaluate(binding.rules().get(1).condition(), frame));
  }

  @Test
  void ruleWithoutConditionIsUnconditional() throws Exception {
    final MethodBinding binding =
        RuleBinder.bind(
            snapshot(rule("Garage", "park", "")), Garage.class, method(Garage.class, "park"));

    assertSame(Condition.ALWAYS_TRUE, binding.rules().get(0).condition());
  }

  @Test
  void nonPublicMethodIsAProblem() throws Exception {
    final MethodBinding binding =
        RuleBinder.bind(
            snapshot(rule("Car", "hidden", "")), Car.class, method(Car.class, "hidden"));

    assertTrue(binding.isEmpty());
    assertTrue(binding.problems().get(0).reason().contains("not a public method"));
  }

  @Test
  void unknownPropertyOnFinalTypeIsAProblem() throws Exception {
    final MethodBinding binding =
        RuleBinder.bind(
            snapshot(rule("Garage", "park", "<attribute key=\"x\" path=\"$return.missing\"/>")),
            Garage.class,
            method(Garage.class, "park"));

    assertTrue(binding.isEmpty());
    assertTrue(binding.problems().get(0).reason().contains("'missing'"));
  }

  @Test
  void unknownPropertyOnNonFinalTypeIsResolvedAtRuntime() throws Exception {
    // Car is not final: a subclass may have getColour(), so the rule stays active.
    final MethodBinding binding =
        RuleBinder.bind(
            snapshot(rule("Garage", "park", "<attribute key=\"x\" path=\"$arg0.colour\"/>")),
            Garage.class,
            method(Garage.class, "park"));

    assertEquals(1, binding.rules().size());
  }

  @Test
  void conditionTypeErrorIsAProblem() throws Exception {
    final MethodBinding binding =
        RuleBinder.bind(
            snapshot(rule("Garage", "park", "<when>$arg0.brand == 42</when>")),
            Garage.class,
            method(Garage.class, "park"));

    assertTrue(binding.isEmpty());
    assertTrue(binding.problems().get(0).reason().startsWith("condition: type mismatch"));
  }

  @Test
  void otherRulesSurviveAProblem() throws Exception {
    final MethodBinding binding =
        RuleBinder.bind(
            snapshot(
                rule("Car", "drive", "<when>$arg0.brand == 42</when>")
                    + rule("Vehicle", "drive", "<when>$arg0.brand == 'bmw'</when>")),
            Car.class,
            method(Car.class, "drive"));

    assertEquals(1, binding.problems().size());
    assertEquals(1, binding.problems().get(0).rule().position());
    assertEquals(
        List.of(2), binding.rules().stream().map(r -> r.rule().definition().position()).toList());
  }
}
