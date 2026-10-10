package org.otel.agent.core.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.otel.agent.core.config.RuleDefinition.Selector;

class TransformationPlanTest {

  private static final Selector PARK = new Selector("com.example.Garage", "park");
  private static final Selector DRIVE = new Selector("com.example.Car", "drive");

  @Test
  void argumentModeFollowsHighestArgumentIndex() {
    assertFalse(PlanEntry.of(PARK, -1).allArguments());
    assertFalse(PlanEntry.of(PARK, 3).allArguments());
    assertTrue(PlanEntry.of(PARK, 4).allArguments());
  }

  @Test
  void lookupByClassAndMethodName() {
    final TransformationPlan plan =
        TransformationPlan.of(List.of(PlanEntry.of(PARK, 0), PlanEntry.of(DRIVE, 0)));

    assertEquals(Set.of("com.example.Garage", "com.example.Car"), plan.classNames());
    assertEquals(Optional.of(PlanEntry.of(PARK, 0)), plan.entry("com.example.Garage", "park"));
    assertEquals(Optional.empty(), plan.entry("com.example.Garage", "drive"));
    assertEquals(Optional.empty(), plan.entry("com.example.Other", "park"));
  }

  @Test
  void equalEntriesMeanEqualPlansAndFingerprints() {
    final TransformationPlan first =
        TransformationPlan.of(List.of(PlanEntry.of(PARK, 0), PlanEntry.of(DRIVE, 1)));
    final TransformationPlan second =
        TransformationPlan.of(List.of(PlanEntry.of(DRIVE, 2), PlanEntry.of(PARK, 3)));

    assertEquals(first, second);
    assertEquals(first.fingerprint(), second.fingerprint());
  }

  @Test
  void argumentModeIsPartOfTheFootprint() {
    assertNotEquals(
        TransformationPlan.of(List.of(PlanEntry.of(PARK, 0))),
        TransformationPlan.of(List.of(PlanEntry.of(PARK, 5))));
  }

  @Test
  void emptyPlan() {
    assertTrue(TransformationPlan.of(List.of()).isEmpty());
    assertEquals(TransformationPlan.EMPTY, TransformationPlan.of(List.of()));
  }

  @Test
  void diffAddedRemovedAndChanged() {
    final TransformationPlan before =
        TransformationPlan.of(List.of(PlanEntry.of(PARK, 0), PlanEntry.of(DRIVE, 0)));
    final Selector wash = new Selector("com.example.Wash", "run");
    final TransformationPlan after =
        TransformationPlan.of(List.of(PlanEntry.of(PARK, 6), PlanEntry.of(wash, 0)));

    final PlanDiff diff = PlanDiff.between(before, after);

    assertEquals(Set.of(PlanEntry.of(PARK, 6), PlanEntry.of(wash, 0)), diff.added());
    assertEquals(Set.of(PlanEntry.of(PARK, 0), PlanEntry.of(DRIVE, 0)), diff.removed());
    assertEquals(
        Set.of("com.example.Car", "com.example.Garage", "com.example.Wash"),
        diff.affectedClassNames());
  }

  @Test
  void identicalPlansHaveAnEmptyDiff() {
    final TransformationPlan plan = TransformationPlan.of(List.of(PlanEntry.of(PARK, 0)));

    assertTrue(PlanDiff.between(plan, plan).isEmpty());
  }

  @Test
  void removingEverythingAffectsAllClasses() {
    final TransformationPlan plan = TransformationPlan.of(List.of(PlanEntry.of(PARK, 0)));

    assertEquals(
        Set.of("com.example.Garage"),
        PlanDiff.between(plan, TransformationPlan.EMPTY).affectedClassNames());
  }
}
