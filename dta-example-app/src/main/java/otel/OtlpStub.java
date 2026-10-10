package otel;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Minimal OTLP/HTTP trace receiver for local runs, tests and benchmarks.
 *
 * <p>Every non-empty request body is decoded as an {@code ExportTraceServiceRequest} and appended
 * in text form to {@code OTLP_STUB_FILE} (default {@code telemetry.log}). Host and port come from
 * {@code OTLP_STUB_HOST} (default 127.0.0.1) and {@code OTLP_STUB_PORT} (default 4318).
 */
public final class OtlpStub {

  private OtlpStub() {}

  public static void main(final String[] args) throws IOException {
    final String host = System.getenv().getOrDefault("OTLP_STUB_HOST", "127.0.0.1");
    final int port = Integer.parseInt(System.getenv().getOrDefault("OTLP_STUB_PORT", "4318"));
    final Path output = Path.of(System.getenv().getOrDefault("OTLP_STUB_FILE", "telemetry.log"));

    final HttpServer server = HttpServer.create(new InetSocketAddress(host, port), 0);
    server.createContext("/", exchange -> handle(exchange, output));
    server.start();
    System.out.println("OTLP stub listening on http://" + host + ":" + port + " → " + output);
  }

  private static void handle(final HttpExchange exchange, final Path output) throws IOException {
    try {
      final byte[] body = exchange.getRequestBody().readAllBytes();
      if (body.length > 0) {
        final ExportTraceServiceRequest request = ExportTraceServiceRequest.parseFrom(body);
        append(output, request.toString());
      }
      exchange.sendResponseHeaders(200, -1);
    } catch (IOException | RuntimeException failure) {
      System.err.println("OTLP stub cannot handle request: " + failure);
      exchange.sendResponseHeaders(400, -1);
    } finally {
      exchange.close();
    }
  }

  private static synchronized void append(final Path output, final String content)
      throws IOException {
    Files.writeString(
        output,
        content + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
  }
}
