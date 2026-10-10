package org.otel.agent.extension.instrument;

import java.util.Objects;
import org.otel.agent.core.plan.TransformationPlan;

/**
 * The plan the instrumentation matchers use. Written by the reload coordinator, read by Byte Buddy
 * whenever a class is loaded or retransformed.
 *
 * <p>Static because OpenTelemetry creates the instrumentation module itself (via SPI); the matchers
 * therefore cannot receive it through a constructor.
 */
public final class ActivePlan {

  private static volatile TransformationPlan current = TransformationPlan.EMPTY;

  private ActivePlan() {}

  public static TransformationPlan current() {
    return current;
  }

  public static void set(final TransformationPlan plan) {
    current = Objects.requireNonNull(plan, "plan");
  }
}
