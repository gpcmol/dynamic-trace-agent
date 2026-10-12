package org.otel.agent.extension.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.example.engine.Fixtures.Car;
import com.example.engine.Fixtures.Garage;
import com.example.engine.Fixtures.Passenger;
import com.example.engine.Fixtures.Result;
import com.example.engine.Fixtures.SportsCar;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.otel.agent.core.config.XmlConfigurationParser;
import org.otel.agent.core.snapshot.SnapshotCompiler;
import org.otel.agent.extension.diag.AgentLog;
import org.otel.agent.extension.instrument.SiteRegistry;
import org.otel.agent.extension.instrument.SiteRegistry.SiteInfo;
import org.otel.agent.extension.telemetry.SpanWriter;

@SuppressWarnings("try") // scopes are opened for their side effect
class EnrichmentEngineTest {

  private static final String FIXTURES = Car.class.getEnclosingClass().getName() + "$";

  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
  private final SdkTracerProvider provider =
      SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
  private final Tracer tracer = provider.get("test");
  private final EnrichmentEngine engine =
      new EnrichmentEngine(
          new SpanWriter(provider.get(SpanWriter.TRACER_NAME)), new AgentLog(false));
  private long version = 1;

  @AfterEach
  void close() {
    provider.close();
  }

  private void configure(final String statics, final String rules) throws Exception {
    final String xml =
        """
        <configuration>
          <static>%s</static>
          <dynamic>%s</dynamic>
        </configuration>
        """
            .formatted(statics, rules);
    engine.publish(SnapshotCompiler.compile(new XmlConfigurationParser().parse(xml), version++));
  }

  private static String rule(final String simpleClass, final String method, final String body) {
    return """
        <rule>
          <selector><class name="%s%s"/><method name="%s"/></selector>
          %s
        </rule>
        """
        .formatted(FIXTURES, simpleClass, method, body);
  }

  private static int site(final Class<?> type, final String name) {
    for (final Method method : type.getDeclaredMethods()) {
      if (method.getName().equals(name)) {
        return SiteRegistry.siteOf(
            new SiteInfo(type.getName(), name, MethodResolver.descriptorOf(method)));
      }
    }
    throw new IllegalArgumentException(name);
  }

  private static final Car BMW =
      new Car("bmw", 10_000, List.of(new Passenger("Jan"), new Passenger("Piet")));

  /** Simulates the advice on Garage.park inside a server span; returns that span's data. */
  private SpanData parkInsideSpan(final Car car) {
    final Span server = tracer.spanBuilder("POST /cars").startSpan();
    try (Scope ignored = server.makeCurrent()) {
      engine.onExit(
          site(Garage.class, "park"),
          new Garage(),
          new Result(true, "accepted"),
          car,
          null,
          null,
          null);
    } finally {
      server.end();
    }
    return exporter.getFinishedSpanItems().get(exporter.getFinishedSpanItems().size() - 1);
  }

  @Test
  void enrichesTheCurrentSpanWithStaticAndDynamicAttributes() throws Exception {
    configure(
"""
<attribute key="debug" value="true"/>""",
        rule(
            "Garage",
            "park",
            """
            <when>$arg0.brand == 'bmw' and $arg0.mileage gt 0</when>
            <attribute key="brand" path="$arg0.brand"/>
            <attribute key="mileage" path="$arg0.mileage"/>
            <attribute key="second" path="$arg0.passengers[1].name"/>
            <attribute key="all" path="$arg0.passengers.name"/>
            <attribute key="status" path="$return.status"/>
            """));

    final SpanData span = parkInsideSpan(BMW);

    assertEquals(1, exporter.getFinishedSpanItems().size(), "no extra span");
    assertEquals("true", span.getAttributes().get(AttributeKey.stringKey("debug")));
    assertEquals("bmw", span.getAttributes().get(AttributeKey.stringKey("brand")));
    assertEquals(10_000L, span.getAttributes().get(AttributeKey.longKey("mileage")));
    assertEquals("Piet", span.getAttributes().get(AttributeKey.stringKey("second")));
    assertEquals(
        List.of("Jan", "Piet"), span.getAttributes().get(AttributeKey.stringArrayKey("all")));
    assertEquals("accepted", span.getAttributes().get(AttributeKey.stringKey("status")));
  }

  @Test
  void falseConditionTouchesNothing() throws Exception {
    configure(
"""
<attribute key="debug" value="true"/>""",
        rule(
            "Garage",
            "park",
"""
<when>$arg0.brand == 'audi'</when><attribute key="brand" path="$arg0.brand"/>"""));

    final SpanData span = parkInsideSpan(BMW);

    assertNull(span.getAttributes().get(AttributeKey.stringKey("debug")));
    assertNull(span.getAttributes().get(AttributeKey.stringKey("brand")));
  }

  @Test
  void createsOneFallbackSpanOutsideATrace() throws Exception {
    configure(
        "",
        rule(
            "Garage",
            "park",
"""
<attribute key="brand" path="$arg0.brand"/>"""));

    engine.onExit(
        site(Garage.class, "park"), new Garage(), new Result(true, "ok"), BMW, null, null, null);

    final List<SpanData> spans = exporter.getFinishedSpanItems();
    assertEquals(1, spans.size());
    assertEquals(SpanWriter.FALLBACK_SPAN_NAME, spans.get(0).getName());
    assertEquals(io.opentelemetry.api.trace.SpanKind.INTERNAL, spans.get(0).getKind());
    assertEquals("bmw", spans.get(0).getAttributes().get(AttributeKey.stringKey("brand")));
  }

  @Test
  void failingGetterSkipsOnlyThatAttribute() throws Exception {
    configure(
        "",
        rule(
            "Garage",
            "park",
            """
            <attribute key="broken" path="$arg0.broken"/>
            <attribute key="brand" path="$arg0.brand"/>
            """));

    final SpanData span = parkInsideSpan(BMW);

    assertNull(span.getAttributes().get(AttributeKey.stringKey("broken")));
    assertEquals("bmw", span.getAttributes().get(AttributeKey.stringKey("brand")));
  }

  @Test
  void eachMatchingSelectorUsesItsOwnCondition() throws Exception {
    configure(
        "",
        rule(
                "Car",
                "drive",
                """
                <when>$return == 'nope'</when>
                <attribute key="car" path="$this.brand"/>
                """)
            + rule(
                "SportsCar",
                "drive",
                """
                <when>$return == 'VROOM'</when>
                <attribute key="sports" path="$this.brand"/>
                """));

    final Span server = tracer.spanBuilder("drive").startSpan();
    try (Scope ignored = server.makeCurrent()) {
      engine.onExit(
          site(SportsCar.class, "drive"),
          new SportsCar("porsche"),
          "VROOM",
          null,
          null,
          null,
          null);
    } finally {
      server.end();
    }

    final SpanData span = exporter.getFinishedSpanItems().get(0);
    assertNull(span.getAttributes().get(AttributeKey.stringKey("car")));
    assertEquals("porsche", span.getAttributes().get(AttributeKey.stringKey("sports")));
  }

  @Test
  void allArgumentsVariantReadsHighArguments() throws Exception {
    configure(
        "",
        rule(
            "Garage",
            "many",
"""
<attribute key="fifthBlank" path="$arg4.blank"/>"""));

    engine.onExitAllArgs(
        site(Garage.class, "many"), new Garage(), "e", new Object[] {"a", "b", "c", "d", "eeee"});

    final SpanData span = exporter.getFinishedSpanItems().get(0);
    assertEquals(false, span.getAttributes().get(AttributeKey.booleanKey("fifthBlank")));
  }

  @Test
  void disabledConfigurationDoesNothing() throws Exception {
    configure("", "");

    engine.onExit(site(Garage.class, "park"), new Garage(), null, BMW, null, null, null);

    assertTrue(exporter.getFinishedSpanItems().isEmpty());
  }

  @Test
  void reloadRebindsWithTheNewConfiguration() throws Exception {
    configure(
        "",
        rule(
            "Garage",
            "park",
"""
<attribute key="brand" path="$arg0.brand"/>"""));
    assertEquals("bmw", parkInsideSpan(BMW).getAttributes().get(AttributeKey.stringKey("brand")));

    configure(
        "",
        rule(
            "Garage",
            "park",
"""
<attribute key="km" path="$arg0.mileage"/>"""));
    final SpanData span = parkInsideSpan(BMW);

    assertNull(span.getAttributes().get(AttributeKey.stringKey("brand")));
    assertEquals(10_000L, span.getAttributes().get(AttributeKey.longKey("km")));
  }

  @Test
  void nestedExitOnTheSameThreadIsSkipped() throws Exception {
    configure(
        "",
        rule(
            "Garage",
            "park",
"""
<attribute key="brand" path="$arg0.brand"/>"""));
    final ExitFrameSlot outer = ExitFrameSlot.tryAcquire();
    try {
      engine.onExit(site(Garage.class, "park"), new Garage(), null, BMW, null, null, null);
    } finally {
      outer.release();
    }

    assertTrue(exporter.getFinishedSpanItems().isEmpty());
  }
}
