package org.otel.agent.benchmarks.engine;

import com.example.bench.BenchModel.Car;
import com.example.bench.BenchModel.Garage;
import com.example.bench.BenchModel.Passenger;
import com.example.bench.BenchModel.Result;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.otel.agent.core.config.XmlConfigurationParser;
import org.otel.agent.core.snapshot.SnapshotCompiler;
import org.otel.agent.extension.diag.AgentLog;
import org.otel.agent.extension.engine.EnrichmentEngine;
import org.otel.agent.extension.instrument.SiteRegistry;
import org.otel.agent.extension.telemetry.SpanWriter;

/**
 * Cost of one exit of an instrumented method in the engine (everything after the advice called the
 * gateway), spec {@code benchmarking} "Micro benchmarks" and {@code hot-path-performance}.
 *
 * <ul>
 *   <li>{@code conditionFalse}: the most frequent production path — must not allocate.
 *   <li>{@code noMatchingRule}: an instrumented method whose binding has no rule.
 *   <li>{@code conditionTrue}: full enrichment of a recording span with {@code attributes} dynamic
 *       attributes plus 5 static ones (scaling series 0 / 5 / 20).
 * </ul>
 *
 * Run: {@code java -jar dta-benchmarks/target/benchmarks.jar EngineBenchmark -prof gc}.
 */
@State(org.openjdk.jmh.annotations.Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class EngineBenchmark {

  private static final String CAR_DESCRIPTOR =
      "(Lcom/example/bench/BenchModel$Car;)Lcom/example/bench/BenchModel$Result;";
  private static final String[] PATHS = {
    "$arg0.brand", "$arg0.model", "$arg0.year", "$arg0.mileage", "$arg0.passengers[1].name"
  };

  @Param({"0", "5", "20"})
  public int attributes;

  private final Garage garage = new Garage();
  private final Car bmw =
      new Car("bmw", "x5", 2024, 50_000, List.of(new Passenger("Jan"), new Passenger("Piet")));
  private final Car tesla = new Car("tesla", "3", 2023, 10, List.of(new Passenger("Eva")));
  private final Result result = new Result(true, "accepted");

  private SdkTracerProvider provider;
  private EnrichmentEngine engine;
  private Span span;
  private io.opentelemetry.context.Scope scope;
  private int parkSite;
  private int inspectSite;

  @Setup(Level.Trial)
  public void setUp() throws Exception {
    provider = SdkTracerProvider.builder().build();
    engine = new EnrichmentEngine(new SpanWriter(provider.get("bench")), new AgentLog(false));
    engine.publish(
        SnapshotCompiler.compile(new XmlConfigurationParser().parse(configuration()), 1));
    parkSite = site("park");
    inspectSite = site("inspect");
  }

  /** A fresh recording span per iteration, so attribute maps do not grow without bound. */
  @Setup(Level.Iteration)
  @SuppressWarnings("MustBeClosedChecker") // closed in closeSpan(), per JMH iteration
  public void openSpan() {
    span = provider.get("bench").spanBuilder("POST /cars").startSpan();
    scope = span.makeCurrent();
  }

  @TearDown(Level.Iteration)
  public void closeSpan() {
    scope.close();
    span.end();
  }

  @TearDown(Level.Trial)
  public void tearDown() {
    provider.close();
  }

  @Benchmark
  public void conditionFalse() {
    engine.onExit(parkSite, garage, result, tesla, null, null, null);
  }

  @Benchmark
  public void noMatchingRule() {
    engine.onExit(inspectSite, garage, result, bmw, null, null, null);
  }

  @Benchmark
  public void conditionTrue() {
    engine.onExit(parkSite, garage, result, bmw, null, null, null);
  }

  private String configuration() {
    final StringBuilder dynamic = new StringBuilder();
    for (int i = 0; i < attributes; i++) {
      dynamic
          .append("<attribute key=\"a")
          .append(i)
          .append("\" path=\"")
          .append(PATHS[i % PATHS.length])
          .append("\"/>");
    }
    return "<configuration><static>"
        + "<attribute key=\"debug\" value=\"true\"/><attribute key=\"domain\" value=\"cars\"/>"
        + "<attribute key=\"team\" value=\"t\"/><attribute key=\"environment\" value=\"prod\"/>"
        + "<attribute key=\"region\" value=\"eu\"/></static><dynamic>"
        + "<rule><selector><class name=\""
        + Garage.class.getName()
        + "\"/><method name=\"park\"/></selector>"
        + "<when>$arg0.brand in ['bmw', 'audi', 'vw', 'mercedes'] and $arg0.mileage gt 0</when>"
        + dynamic
        + "</rule>"
        + "<rule><selector><class name=\"com.example.Elsewhere\"/>"
        + "<method name=\"inspect\"/></selector></rule>"
        + "</dynamic></configuration>";
  }

  private static int site(final String method) {
    return SiteRegistry.siteOf(
        new SiteRegistry.SiteInfo(Garage.class.getName(), method, CAR_DESCRIPTOR));
  }
}
