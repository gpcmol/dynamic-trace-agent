package org.otel.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.opentelemetry.proto.trace.v1.Span.SpanKind;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.otel.agent.it.OtlpReceiver.ReceivedSpan;

/**
 * End-to-end: one garage JVM with the real OpenTelemetry agent and the packaged extension, driven
 * through a live sequence of configuration changes — as an operator would during an incident.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class EnrichmentIT {

  private static final String BMW =
      "{\"brand\":\"bmw\",\"model\":\"x5\",\"year\":2024,\"mileage\":10000,"
          + "\"passengers\":[{\"name\":\"Jan\"},{\"name\":\"Piet\"}]}";
  private static final String TESLA =
      "{\"brand\":\"tesla\",\"model\":\"3\",\"mileage\":10,\"passengers\":[{\"name\":\"Eva\"}]}";
  private static final String GARAGE_SELECTOR =
      "<selector><class name=\"com.example.Garage\"/><method name=\"park\"/></selector>";

  private static Path directory;
  private static Path config;
  private static OtlpReceiver receiver;
  private static InstrumentedApp app;
  private static int loads;

  @BeforeAll
  static void start() throws Exception {
    directory = Files.createTempDirectory("dta-it");
    config = directory.resolve("agent-config.xml");
    Files.copy(Path.of(System.getProperty("it.reference.config")), config);
    receiver = new OtlpReceiver();
    app = InstrumentedApp.start(directory, config, receiver.endpoint(), freePort());
    awaitLoad(1, 0);
  }

  @AfterAll
  static void stop() throws Exception {
    app.close();
    receiver.close();
  }

  @BeforeEach
  void clearSpans() {
    receiver.clear();
  }

  @Test
  @Order(1)
  void matchingCallEnrichesTheServerSpan() throws Exception {
    app.post("/cars", BMW);

    final ReceivedSpan span =
        receiver.await(s -> "bmw".equals(s.attribute("brand")), "with brand=bmw");

    assertEquals(SpanKind.SPAN_KIND_SERVER, span.kind(), "existing span, no new one");
    assertEquals("true", span.attribute("debug"));
    assertEquals("cars", span.attribute("domain"));
    assertEquals("x5", span.attribute("model"));
    assertEquals(2024L, span.attribute("year"));
    assertEquals(10_000L, span.attribute("mileage"));
    assertEquals("Piet", span.attribute("passengers"));
    assertEquals(true, span.attribute("accepted"));
    assertEquals("accepted", span.attribute("status"));
  }

  @Test
  @Order(2)
  void nonMatchingCallLeavesTheSpanUntouched() throws Exception {
    app.post("/cars", TESLA);

    final ReceivedSpan span =
        receiver.await(s -> s.kind() == SpanKind.SPAN_KIND_SERVER, "for the request");

    assertNull(span.attribute("brand"));
    assertNull(span.attribute("debug"));
  }

  @Test
  @Order(3)
  void callOutsideATraceGetsOneFallbackSpan() throws Exception {
    app.post("/cars/background", BMW);

    final ReceivedSpan span =
        receiver.await(s -> "dynamic-trace.enrichment".equals(s.name()), "fallback span");

    assertEquals(SpanKind.SPAN_KIND_INTERNAL, span.kind());
    assertEquals("bmw", span.attribute("brand"));
    assertEquals(1, receiver.spans().stream().filter(s -> s.name().equals(span.name())).count());
  }

  @Test
  @Order(4)
  void attributeChangeAppliesWithoutRetransformation() throws Exception {
    write(rule("<attribute key=\"km\" path=\"$arg0.mileage\"/>"));
    awaitLoad(1, 0);

    app.post("/cars", TESLA);

    final ReceivedSpan span = receiver.await(s -> s.attribute("km") != null, "with km");
    assertEquals(10L, span.attribute("km"));
    assertNull(span.attribute("brand"));
  }

  @Test
  @Order(5)
  void invalidConfigurationKeepsTheCurrentOne() throws Exception {
    write("<configuration><dynamic><unknown/></dynamic></configuration>");
    app.awaitLog("configuration rejected");

    app.post("/cars", BMW);

    receiver.await(s -> Long.valueOf(10_000).equals(s.attribute("km")), "still enriched with km");
  }

  @Test
  @Order(6)
  void unknownPropertyIgnoresTheRuleWithAWarning() throws Exception {
    write(rule("<attribute key=\"x\" path=\"$return.missing\"/>"));
    awaitLoad(1, 0);

    app.post("/cars", BMW);

    app.awaitLog("is ignored for com.example.Garage");
    final ReceivedSpan span =
        receiver.await(s -> s.kind() == SpanKind.SPAN_KIND_SERVER, "for the request");
    assertNull(span.attribute("x"));
  }

  @Test
  @Order(7)
  void emptyConfigurationRemovesTheAdvice() throws Exception {
    write("<configuration/>");
    awaitLoad(0, 1);

    app.post("/cars", BMW);

    final ReceivedSpan span =
        receiver.await(s -> s.kind() == SpanKind.SPAN_KIND_SERVER, "for the request");
    assertNull(span.attribute("brand"));
  }

  @Test
  @Order(8)
  void restoredConfigurationInstrumentsTheLoadedClassAgain() throws Exception {
    Files.copy(
        Path.of(System.getProperty("it.reference.config")),
        config,
        StandardCopyOption.REPLACE_EXISTING);
    awaitLoad(1, 1);

    app.post("/cars", BMW);

    receiver.await(s -> "bmw".equals(s.attribute("brand")), "with brand=bmw");
  }

  @Test
  @Order(9)
  void deletedFileDisablesEnrichment() throws Exception {
    Files.delete(config);
    app.awaitLog("does not exist: enrichment is disabled");
    app.awaitLog("disabled: configuration file removed");

    app.post("/cars", BMW);

    final ReceivedSpan span =
        receiver.await(s -> s.kind() == SpanKind.SPAN_KIND_SERVER, "for the request");
    assertNull(span.attribute("brand"));
    assertFalse(app.log().contains("Exception"), "no exception may reach the log:\n" + app.log());
  }

  @Test
  @Order(10)
  void typoInMethodNameIsReported() throws Exception {
    write(
        "<configuration><dynamic><rule><selector><class name=\"com.example.Garage\"/>"
            + "<method name=\"parkk\"/></selector></rule></dynamic></configuration>");
    awaitLoad(1, 1);

    app.awaitLog("parkk is not a public method of com.example.Garage");
  }

  private static String rule(final String body) {
    return "<configuration><static><attribute key=\"debug\" value=\"true\"/></static><dynamic><rule>"
        + GARAGE_SELECTOR
        + body
        + "</rule></dynamic></configuration>";
  }

  /** Replaces the configuration file with a new modification time. */
  private static void write(final String content) throws IOException, InterruptedException {
    Thread.sleep(1_100); // file systems with 1 s timestamp resolution
    Files.writeString(config, content);
  }

  private static void awaitLoad(final int rules, final int retransformed)
      throws IOException, InterruptedException {
    loads++;
    app.awaitLog(
        "loaded configuration "
            + loads
            + ": "
            + rules
            + " rule(s) active, 0 ignored, "
            + retransformed
            + " class(es) retransformed");
  }

  private static int freePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }
}
