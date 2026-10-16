package org.otel.agent.it;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The garage application running in its own JVM with the OpenTelemetry agent and the extension,
 * attached exactly like the OpenTelemetry Operator does it: {@code JAVA_TOOL_OPTIONS=-javaagent:…}
 * and {@code OTEL_JAVAAGENT_EXTENSIONS=<directory>}. The JVM is {@code -Dit.java.home=…}, or the
 * one running the tests when that is not set.
 */
final class InstrumentedApp implements AutoCloseable {

  private final Process process;
  private final Path log;
  private final int port;
  private final HttpClient client = HttpClient.newHttpClient();

  private InstrumentedApp(final Process process, final Path log, final int port) {
    this.process = process;
    this.log = log;
    this.port = port;
  }

  static InstrumentedApp start(
      final Path workDirectory, final Path configFile, final String otlpEndpoint, final int port)
      throws IOException, InterruptedException {
    final Path extensions = Files.createDirectories(workDirectory.resolve("extensions"));
    Files.copy(
        Path.of(System.getProperty("it.extension.jar")),
        extensions.resolve("dynamic-trace-agent.jar"));
    final Path log = workDirectory.resolve("app.log");

    final List<String> command = new ArrayList<>();
    // The application's JVM may differ from the one running the tests (CI: Java 17 and 21).
    final String javaHome = System.getProperty("it.java.home", "");
    command.add(
        Path.of(javaHome.isBlank() ? System.getProperty("java.home") : javaHome, "bin", "java")
            .toString());
    command.add("-jar");
    command.add(System.getProperty("it.app.jar"));
    final ProcessBuilder builder = new ProcessBuilder(command);
    final Map<String, String> environment = builder.environment();
    environment.put("JAVA_TOOL_OPTIONS", "-javaagent:" + System.getProperty("it.agent.jar"));
    environment.put("OTEL_JAVAAGENT_EXTENSIONS", extensions.toString());
    environment.put("DYNAMIC_TRACE_CONFIG_FILE", configFile.toString());
    environment.put("DYNAMIC_TRACE_CONFIG_RELOAD_INTERVAL", "1");
    environment.put("OTEL_SERVICE_NAME", "garage-it");
    environment.put("OTEL_EXPORTER_OTLP_PROTOCOL", "http/protobuf");
    environment.put("OTEL_EXPORTER_OTLP_ENDPOINT", otlpEndpoint);
    environment.put("OTEL_METRICS_EXPORTER", "none");
    environment.put("OTEL_LOGS_EXPORTER", "none");
    environment.put("OTEL_BSP_SCHEDULE_DELAY", "100");
    environment.put("PORT", Integer.toString(port));
    builder.redirectErrorStream(true).redirectOutput(log.toFile());

    final InstrumentedApp app = new InstrumentedApp(builder.start(), log, port);
    app.awaitReady();
    return app;
  }

  /** Posts a car to {@code path} and returns the HTTP status. */
  int post(final String path, final String json) throws IOException, InterruptedException {
    final HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(json))
            .build();
    return client.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
  }

  String log() throws IOException {
    return Files.readString(log, StandardCharsets.UTF_8);
  }

  /** Waits until the log contains {@code text}, e.g. after a configuration reload. */
  void awaitLog(final String text) throws IOException, InterruptedException {
    final long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
    while (System.nanoTime() < deadline) {
      if (log().contains(text)) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("log does not contain '" + text + "':\n" + log());
  }

  private void awaitReady() throws IOException, InterruptedException {
    final long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      if (!process.isAlive()) {
        throw new AssertionError("application exited:\n" + log());
      }
      if (log().contains("Garage running")) {
        return;
      }
      Thread.sleep(100);
    }
    throw new AssertionError("application did not start:\n" + log());
  }

  @Override
  public void close() {
    process.destroy();
    try {
      process.waitFor();
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
    }
  }
}
