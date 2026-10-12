package org.otel.agent.extension.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.otel.agent.core.plan.TransformationPlan;
import org.otel.agent.core.snapshot.Snapshot;
import org.otel.agent.extension.diag.AgentLog;

class ReloadCoordinatorTest {

  private static final String GARAGE_RULE =
      """
      <configuration>
        <dynamic>
          <rule>
            <selector><class name="com.example.Garage"/><method name="park"/></selector>
          </rule>
        </dynamic>
      </configuration>
      """;

  /** Records what the coordinator publishes, in order. */
  private static final class RecordingTarget implements ReloadCoordinator.Target {
    final List<String> calls = new ArrayList<>();
    Snapshot snapshot = Snapshot.disabled();
    TransformationPlan plan = TransformationPlan.EMPTY;

    @Override
    public Snapshot activeSnapshot() {
      return snapshot;
    }

    @Override
    public void publish(final Snapshot next) {
      calls.add("snapshot");
      snapshot = next;
    }

    @Override
    public void publishPlan(final TransformationPlan next) {
      calls.add("plan");
      plan = next;
    }
  }

  private final RecordingTarget target = new RecordingTarget();
  private final ReloadCoordinator coordinator =
      new ReloadCoordinator(
          target, new Retransformer(Optional.empty(), new AgentLog(false)), new AgentLog(false));

  @Test
  void validConfigurationIsPublishedSnapshotFirst() {
    coordinator.contentChanged(GARAGE_RULE);

    assertEquals(List.of("snapshot", "plan"), target.calls);
    assertTrue(target.snapshot.isEnabled());
    assertEquals(1, target.snapshot.version());
    assertEquals(target.snapshot.plan(), target.plan);
  }

  @Test
  void invalidConfigurationKeepsTheCurrentOne() {
    coordinator.contentChanged(GARAGE_RULE);
    final Snapshot valid = target.snapshot;

    coordinator.contentChanged("<configuration><oops/></configuration>");

    assertSame(valid, target.snapshot);
  }

  @Test
  void versionsIncreaseOnlyForValidConfigurations() {
    coordinator.contentChanged(GARAGE_RULE);
    coordinator.contentChanged("not xml");
    coordinator.contentChanged("<configuration/>");

    assertEquals(2, target.snapshot.version());
    assertFalse(target.snapshot.isEnabled());
    assertTrue(target.plan.isEmpty());
  }

  @Test
  void missingFileDisables() {
    coordinator.contentChanged(GARAGE_RULE);

    coordinator.fileMissing();

    assertFalse(target.snapshot.isEnabled());
    assertTrue(target.plan.isEmpty());
  }

  @Test
  void missingFileWhileAlreadyDisabledPublishesNothing() {
    coordinator.fileMissing();

    assertTrue(target.calls.isEmpty());
  }
}
