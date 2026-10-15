package org.otel.agent.extension.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.engine.Fixtures.Car;
import com.example.engine.Fixtures.Garage;
import com.example.engine.Fixtures.Passenger;
import com.example.engine.Fixtures.Result;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.otel.agent.core.binding.MethodBinding;
import org.otel.agent.core.binding.RuleBinder;
import org.otel.agent.core.config.XmlConfigurationParser;
import org.otel.agent.core.dsl.Condition;
import org.otel.agent.core.snapshot.Snapshot;
import org.otel.agent.core.snapshot.SnapshotCompiler;
import org.otel.agent.extension.diag.AgentLog;
import org.otel.agent.extension.instrument.SiteRegistry;
import org.otel.agent.extension.instrument.SiteRegistry.SiteInfo;
import org.otel.agent.extension.telemetry.SpanWriter;

/** Debug mode explains attributes that are not written, once per (rule, attribute). */
@SuppressWarnings("try") // scopes are opened for their side effect
class RuleDiagnosticsTest {

  private static final String GARAGE = Garage.class.getName();
  private static final Car BMW =
      new Car("bmw", 10_000, List.of(new Passenger("Jan"), new Passenger("Piet")));

  private final List<String> messages = new CopyOnWriteArrayList<>();
  private final Handler capture =
      new Handler() {
        @Override
        public void publish(final LogRecord record) {
          messages.add(record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
  private final SdkTracerProvider provider =
      SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();

  @BeforeEach
  void attach() {
    Logger.getLogger("org.otel.agent").addHandler(capture);
  }

  @AfterEach
  void detach() {
    Logger.getLogger("org.otel.agent").removeHandler(capture);
    provider.close();
  }

  private static Snapshot snapshot(final String body) throws Exception {
    final String xml =
        """
        <configuration><dynamic><rule>
          <selector><class name="%s"/><method name="park"/></selector>
          %s
        </rule></dynamic></configuration>
        """
            .formatted(GARAGE, body);
    return SnapshotCompiler.compile(new XmlConfigurationParser().parse(xml), 1);
  }

  private static int parkSite() throws Exception {
    final Method park = Garage.class.getMethod("park", Car.class);
    return SiteRegistry.siteOf(new SiteInfo(GARAGE, "park", MethodResolver.descriptorOf(park)));
  }

  private SpanData park(final EnrichmentEngine engine) throws Exception {
    final Span server = provider.get("test").spanBuilder("POST /cars").startSpan();
    try (Scope ignored = server.makeCurrent()) {
      engine.onExit(parkSite(), new Garage(), new Result(true, "ok"), BMW, null, null, null);
    } finally {
      server.end();
    }
    final List<SpanData> spans = exporter.getFinishedSpanItems();
    return spans.get(spans.size() - 1);
  }

  private EnrichmentEngine engine(final boolean debug, final String body) throws Exception {
    final EnrichmentEngine engine =
        new EnrichmentEngine(
            new SpanWriter(provider.get(SpanWriter.TRACER_NAME)), new AgentLog(debug));
    engine.publish(snapshot(body));
    return engine;
  }

  private List<String> about(final String key) {
    return messages.stream().filter(m -> m.contains("'" + key + "'")).toList();
  }

  private static final String ATTRIBUTES =
      """
      <attribute key="brand"   path="$arg0.brand"/>
      <attribute key="broken"  path="$arg0.broken"/>
      <attribute key="missing" path="$arg0.passengers[5].name"/>
      <attribute key="object"  path="$arg0.passengers[0]"/>
      """;

  @Test
  void explainsEachUnwrittenAttributeOnce() throws Exception {
    final EnrichmentEngine engine = engine(true, ATTRIBUTES);

    final SpanData first = park(engine);
    park(engine);
    park(engine);

    assertEquals("bmw", first.getAttributes().get(AttributeKey.stringKey("brand")));
    assertNull(first.getAttributes().get(AttributeKey.stringKey("broken")));
    assertTrue(about("brand").isEmpty(), messages.toString());
    assertEquals(1, about("broken").size(), messages.toString());
    assertTrue(about("broken").get(0).contains("has no value"));
    assertEquals(1, about("missing").size(), messages.toString());
    assertEquals(1, about("object").size(), messages.toString());
    assertTrue(about("object").get(0).contains(Passenger.class.getName()));
    assertTrue(about("object").get(0).contains("cannot be written"));
    assertTrue(about("broken").get(0).contains("rule #1"));
    assertTrue(about("broken").get(0).contains(GARAGE + "#park"));
  }

  @Test
  void neverLogsValues() throws Exception {
    park(engine(true, ATTRIBUTES));

    assertTrue(messages.stream().noneMatch(m -> m.contains("Jan") || m.contains("bmw")));
  }

  @Test
  void silentWithoutDebug() throws Exception {
    final SpanData span = park(engine(false, ATTRIBUTES));

    assertEquals("bmw", span.getAttributes().get(AttributeKey.stringKey("brand")));
    assertTrue(messages.isEmpty(), messages.toString());
  }

  @Test
  void withoutDebugThereAreNoDiagnostics() throws Exception {
    final MethodBinding binding =
        RuleBinder.bind(
            snapshot(ATTRIBUTES), Garage.class, Garage.class.getMethod("park", Car.class));

    assertNull(RuleDiagnostics.forRule(binding.rules().get(0), "Garage#park", new AgentLog(false)));
  }

  @Test
  void conditionFailureCountsAsFalseAndIsReportedOnce() throws Exception {
    final MethodBinding binding =
        RuleBinder.bind(
            snapshot(ATTRIBUTES), Garage.class, Garage.class.getMethod("park", Car.class));
    final RuleDiagnostics diagnostics =
        RuleDiagnostics.forRule(binding.rules().get(0), "Garage#park", new AgentLog(true));
    final Condition failing =
        frame -> {
          throw new IllegalStateException("boom");
        };

    assertFalse(diagnostics.evaluate(failing, null));
    assertFalse(diagnostics.evaluate(failing, null));
    assertTrue(diagnostics.evaluate(Condition.ALWAYS_TRUE, null));
    assertEquals(
        1,
        messages.stream().filter(m -> m.contains("condition failed")).count(),
        messages.toString());
  }
}
