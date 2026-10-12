package org.otel.agent.extension.memory;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.engine.Fixtures.Car;
import com.example.engine.Fixtures.Garage;
import com.example.engine.Fixtures.Passenger;
import io.opentelemetry.api.OpenTelemetry;
import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.otel.agent.core.plan.TransformationPlan;
import org.otel.agent.core.snapshot.Snapshot;
import org.otel.agent.extension.diag.AgentLog;
import org.otel.agent.extension.engine.EnrichmentEngine;
import org.otel.agent.extension.instrument.SiteRegistry;
import org.otel.agent.extension.reload.ReloadCoordinator;
import org.otel.agent.extension.reload.Retransformer;
import org.otel.agent.extension.telemetry.SpanWriter;

/**
 * Spec {@code memory-safety}, "Reload soak": 10,000 reloads with alternating rule sets, with calls
 * in between, must not make heap, loaded classes or threads grow.
 */
class ReloadSoakTest {

  private static final int RELOADS = 10_000;
  private static final String GARAGE = Garage.class.getName();

  /** Allowed growth after warm-up; generous enough for GC noise, far below any real leak. */
  private static final long MAX_HEAP_GROWTH_BYTES = 8L * 1024 * 1024;

  private static final long MAX_CLASS_GROWTH = 50;

  @Test
  void reloadsDoNotAccumulateMemory() throws Exception {
    final EnrichmentEngine engine =
        new EnrichmentEngine(
            new SpanWriter(OpenTelemetry.noop().getTracer("soak")), new AgentLog(false));
    final ReloadCoordinator coordinator =
        new ReloadCoordinator(
            new EngineTarget(engine),
            new Retransformer(Optional.empty(), new AgentLog(false)),
            new AgentLog(false));
    final int site =
        SiteRegistry.siteOf(
            new SiteRegistry.SiteInfo(
                GARAGE,
                "park",
                "(Lcom/example/engine/Fixtures$Car;)Lcom/example/engine/Fixtures$Result;"));
    final Garage garage = new Garage();
    final Car car = new Car("bmw", 1_000, List.of(new Passenger("Jan"), new Passenger("Piet")));

    runReloads(coordinator, engine, site, garage, car, 1_000);
    final long heapBefore = GarbageCollection.usedHeapAfterGc();
    final long classesBefore = loadedClasses();
    final int threadsBefore = Thread.activeCount();

    runReloads(coordinator, engine, site, garage, car, RELOADS);

    final long heapGrowth = GarbageCollection.usedHeapAfterGc() - heapBefore;
    final long classGrowth = loadedClasses() - classesBefore;
    assertTrue(heapGrowth < MAX_HEAP_GROWTH_BYTES, "heap grew by " + heapGrowth + " bytes");
    assertTrue(classGrowth < MAX_CLASS_GROWTH, "loaded classes grew by " + classGrowth);
    assertTrue(Thread.activeCount() <= threadsBefore, "threads grew");
  }

  private static void runReloads(
      final ReloadCoordinator coordinator,
      final EnrichmentEngine engine,
      final int site,
      final Garage garage,
      final Car car,
      final int count) {
    for (int i = 0; i < count; i++) {
      coordinator.contentChanged(configuration(i));
      engine.onExit(site, garage, null, car, null, null, null);
    }
  }

  /** Alternates between rule sets with different conditions, attributes and keys. */
  private static String configuration(final int i) {
    final String attribute =
        i % 3 == 0
            ? """
                <attribute key="brand%d" path="$arg0.brand"/>
                """
                .formatted(i % 7)
            : """
                <attribute key="km" path="$arg0.mileage"/>
                <attribute key="names" path="$arg0.passengers.name"/>
                """;
    final String when = i % 2 == 0 ? "<when>$arg0.mileage gt %d</when>".formatted(i) : "";
    return """
        <configuration>
          <static><attribute key="run" value="%d"/></static>
          <dynamic>
            <rule>
              <selector><class name="%s"/><method name="park"/></selector>
              %s%s
            </rule>
          </dynamic>
        </configuration>
        """
        .formatted(i, GARAGE, when, attribute);
  }

  private static long loadedClasses() {
    return ManagementFactory.getClassLoadingMXBean().getLoadedClassCount();
  }

  private record EngineTarget(EnrichmentEngine engine) implements ReloadCoordinator.Target {
    @Override
    public Snapshot activeSnapshot() {
      return engine.activeSnapshot();
    }

    @Override
    public void publish(final Snapshot snapshot) {
      engine.publish(snapshot);
    }

    @Override
    public void publishPlan(final TransformationPlan plan) {
      // matchers are not involved in this test
    }
  }
}
