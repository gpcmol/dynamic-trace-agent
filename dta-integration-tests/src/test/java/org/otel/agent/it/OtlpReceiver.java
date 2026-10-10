package org.otel.agent.it;

import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/** An OTLP/HTTP trace receiver inside the test JVM. */
final class OtlpReceiver implements AutoCloseable {

  /** A received span, reduced to what the tests check. */
  record ReceivedSpan(String name, Span.SpanKind kind, Map<String, Object> attributes) {
    Object attribute(final String key) {
      return attributes.get(key);
    }
  }

  private final HttpServer server;
  private final List<ReceivedSpan> spans = new ArrayList<>();

  OtlpReceiver() throws IOException {
    server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/",
        exchange -> {
          try {
            final byte[] body = exchange.getRequestBody().readAllBytes();
            if (body.length > 0) {
              store(ExportTraceServiceRequest.parseFrom(body));
            }
            exchange.sendResponseHeaders(200, -1);
          } finally {
            exchange.close();
          }
        });
    server.start();
  }

  String endpoint() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  synchronized List<ReceivedSpan> spans() {
    return List.copyOf(spans);
  }

  synchronized void clear() {
    spans.clear();
  }

  /** Waits until a span matching {@code condition} arrived, or fails after the timeout. */
  ReceivedSpan await(final Predicate<ReceivedSpan> condition, final String description)
      throws InterruptedException {
    final long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      for (final ReceivedSpan span : spans()) {
        if (condition.test(span)) {
          return span;
        }
      }
      Thread.sleep(50);
    }
    throw new AssertionError("no span " + description + " within 15 s; received " + spans());
  }

  private synchronized void store(final ExportTraceServiceRequest request) {
    for (final ResourceSpans resource : request.getResourceSpansList()) {
      for (final ScopeSpans scope : resource.getScopeSpansList()) {
        for (final Span span : scope.getSpansList()) {
          spans.add(new ReceivedSpan(span.getName(), span.getKind(), attributes(span)));
        }
      }
    }
  }

  private static Map<String, Object> attributes(final Span span) {
    final Map<String, Object> attributes = new LinkedHashMap<>();
    for (final KeyValue attribute : span.getAttributesList()) {
      attributes.put(attribute.getKey(), value(attribute.getValue()));
    }
    return attributes;
  }

  private static Object value(final AnyValue value) {
    return switch (value.getValueCase()) {
      case STRING_VALUE -> value.getStringValue();
      case INT_VALUE -> value.getIntValue();
      case DOUBLE_VALUE -> value.getDoubleValue();
      case BOOL_VALUE -> value.getBoolValue();
      case ARRAY_VALUE ->
          value.getArrayValue().getValuesList().stream().map(OtlpReceiver::value).toList();
      default -> value.toString();
    };
  }

  @Override
  public void close() {
    server.stop(0);
  }
}
