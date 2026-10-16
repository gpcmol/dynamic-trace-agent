package org.otel.agent.it;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import org.junit.jupiter.api.Test;

/** Spec classloader-bridge / packaging-and-distribution: layout of dynamic-trace-agent.jar. */
class PackagingIT {

  private static final int JAVA_17_CLASS_FILE_VERSION = 61;

  @Test
  void extensionJarHasTheExpectedLayout() throws Exception {
    try (JarFile jar = new JarFile(Path.of(System.getProperty("it.extension.jar")).toFile())) {
      assertFalse(
          jar.stream().anyMatch(entry -> entry.getName().startsWith("org/otel/agent/gateway/")),
          "the gateway must not be loadable from the extension classloader");
      assertTrue(
          jar.getEntry("dta-gateway/org/otel/agent/gateway/EnrichmentGateway.class") != null);
      assertTrue(jar.getEntry("dta-gateway/org/otel/agent/gateway/ExitHandler.class") != null);
      for (final String service :
          new String[] {
            "io.opentelemetry.javaagent.extension.AgentListener",
            "io.opentelemetry.javaagent.extension.instrumentation.InstrumentationModule",
            "io.opentelemetry.javaagent.tooling.bootstrap.BootstrapPackagesConfigurer"
          }) {
        assertTrue(jar.getEntry("META-INF/services/" + service) != null, service);
      }
      assertFalse(
          jar.stream().anyMatch(entry -> entry.getName().startsWith("net/bytebuddy/")),
          "Byte Buddy is provided by the agent and must not be shaded in");
      assertFalse(
          jar.stream().anyMatch(entry -> entry.getName().startsWith("io/opentelemetry/")),
          "OpenTelemetry is provided by the agent and must not be shaded in");
    }
  }

  @Test
  void extensionJarRunsOnJava17() throws Exception {
    try (JarFile jar = new JarFile(Path.of(System.getProperty("it.extension.jar")).toFile())) {
      final List<JarEntry> classes =
          jar.stream().filter(entry -> entry.getName().endsWith(".class")).toList();
      assertFalse(classes.isEmpty());
      for (final JarEntry entry : classes) {
        try (DataInputStream in = new DataInputStream(jar.getInputStream(entry))) {
          assertEquals(0xCAFEBABE, in.readInt(), entry.getName());
          in.readUnsignedShort(); // minor version
          final int major = in.readUnsignedShort();
          assertTrue(major <= JAVA_17_CLASS_FILE_VERSION, entry.getName() + ": version " + major);
        }
      }
    }
  }
}
