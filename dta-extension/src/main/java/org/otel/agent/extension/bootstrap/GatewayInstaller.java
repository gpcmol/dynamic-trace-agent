package org.otel.agent.extension.bootstrap;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.bytebuddy.dynamic.loading.ClassInjector;

/**
 * Defines the gateway classes in the bootstrap classloader (decision D3, spike S2).
 *
 * <p>The extension jar contains the gateway class files under {@code dta-gateway/} — outside the
 * package path, so the extension classloader can never load its own copy. They are read as bytes
 * and defined directly in bootstrap with Byte Buddy's {@code ClassInjector.UsingUnsafe}: no file is
 * written, so read-only root filesystems work.
 *
 * <p>This class must not reference gateway types: they only exist after {@link #install()}.
 */
final class GatewayInstaller {

  static final List<String> GATEWAY_CLASSES =
      List.of("org.otel.agent.gateway.ExitHandler", "org.otel.agent.gateway.EnrichmentGateway");

  private static final String EMBEDDED_ROOT = "dta-gateway/";

  private GatewayInstaller() {}

  /**
   * Installs the gateway unless it is already present.
   *
   * @throws IllegalStateException when the gateway cannot be installed; the extension then stays
   *     inactive
   */
  static void install() throws IOException {
    if (alreadyInstalled()) {
      return;
    }
    if (!ClassInjector.UsingUnsafe.isAvailable()) {
      throw new IllegalStateException(
          "class injection into the bootstrap classloader is unavailable");
    }
    ClassInjector.UsingUnsafe.ofBootLoader().injectRaw(embeddedClasses());
  }

  private static boolean alreadyInstalled() {
    try {
      Class.forName(GATEWAY_CLASSES.get(0), false, null);
      return true;
    } catch (ClassNotFoundException absent) {
      return false;
    }
  }

  private static Map<String, byte[]> embeddedClasses() throws IOException {
    final Map<String, byte[]> classes = new LinkedHashMap<>();
    for (final String name : GATEWAY_CLASSES) {
      final String resource = EMBEDDED_ROOT + name.replace('.', '/') + ".class";
      try (InputStream input =
          GatewayInstaller.class.getClassLoader().getResourceAsStream(resource)) {
        if (input == null) {
          throw new IOException("gateway class missing from the extension jar: " + resource);
        }
        classes.put(name, input.readAllBytes());
      }
    }
    return classes;
  }
}
