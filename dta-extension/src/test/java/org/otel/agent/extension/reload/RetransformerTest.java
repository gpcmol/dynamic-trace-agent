package org.otel.agent.extension.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.engine.Fixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.otel.agent.core.config.RuleDefinition.Selector;
import org.otel.agent.core.plan.PlanEntry;
import org.otel.agent.core.plan.TransformationPlan;

class RetransformerTest {

  private static TransformationPlan plan(final String className, final String method) {
    return TransformationPlan.of(List.of(PlanEntry.of(new Selector(className, method), 0)));
  }

  @Test
  void typoInClassNameIsReported() {
    final Map<String, String> problems =
        Retransformer.selectorProblems(
            plan("com.example.Garag", "park"), new Class<?>[] {Fixtures.Garage.class});

    assertEquals(1, problems.size());
    assertTrue(problems.values().iterator().next().contains("not found or not loaded yet"));
  }

  @Test
  void typoInMethodNameIsReported() {
    final Map<String, String> problems =
        Retransformer.selectorProblems(
            plan(Fixtures.Garage.class.getName(), "parkk"), new Class<?>[] {Fixtures.Garage.class});

    assertEquals(1, problems.size());
    assertTrue(problems.values().iterator().next().contains("parkk is not a public method of"));
  }

  @Test
  void correctSelectorIsFine() {
    assertTrue(
        Retransformer.selectorProblems(
                plan(Fixtures.Garage.class.getName(), "park"),
                new Class<?>[] {Fixtures.Garage.class})
            .isEmpty());
  }
}
