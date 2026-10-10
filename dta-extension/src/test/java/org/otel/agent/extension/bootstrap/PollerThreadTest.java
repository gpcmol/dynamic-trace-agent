package org.otel.agent.extension.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.OpenTelemetry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.otel.agent.extension.diag.AgentLog;
import org.otel.agent.extension.reload.ReloadSettings;

/**
 * Spec {@code memory-safety}, "Threads do not pin classloaders": one daemon poller without a
 * context classloader, even when started from a thread whose context classloader is an application
 * loader.
 */
class PollerThreadTest {

  @TempDir Path directory;

  @Test
  void pollerIsASingleDaemonWithoutContextClassLoader() throws Exception {
    final Path file = Files.writeString(directory.resolve("agent-config.xml"), "<configuration/>");
    final ClassLoader applicationLoader = new ClassLoader("application", null) {};
    final Thread starter =
        new Thread(
            () ->
                ExtensionRuntime.start(
                    OpenTelemetry.noop(),
                    Optional.empty(),
                    new ReloadSettings(Optional.of(file), Duration.ofSeconds(60)),
                    new AgentLog(false)));
    starter.setContextClassLoader(applicationLoader);
    starter.start();
    starter.join();

    final List<Thread> pollers =
        Thread.getAllStackTraces().keySet().stream()
            .filter(thread -> thread.getName().equals("dynamic-trace-agent-config"))
            .toList();
    assertEquals(1, pollers.size());
    assertTrue(pollers.get(0).isDaemon());
    assertNull(pollers.get(0).getContextClassLoader());
  }
}
