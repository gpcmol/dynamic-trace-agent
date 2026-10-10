package org.otel.agent.gateway;

/**
 * Static entry point called by advice woven into application classes.
 *
 * <p>Lives in the bootstrap classloader so every application classloader can see it. Holds a single
 * reference to the engine's {@link ExitHandler}; when none is installed every call is a no-op.
 * Contains no logic on purpose — see {@code docs/03-classloaders.md}.
 */
public final class EnrichmentGateway {

  private static volatile ExitHandler handler;

  private EnrichmentGateway() {}

  /** Registers the engine. Passing {@code null} disables enrichment. */
  public static void install(final ExitHandler newHandler) {
    handler = newHandler;
  }

  public static void onExit(
      final int site,
      final Object self,
      final Object returned,
      final Object a0,
      final Object a1,
      final Object a2,
      final Object a3) {
    final ExitHandler current = handler;
    if (current != null) {
      current.onExit(site, self, returned, a0, a1, a2, a3);
    }
  }

  public static void onExitAllArgs(
      final int site, final Object self, final Object returned, final Object[] arguments) {
    final ExitHandler current = handler;
    if (current != null) {
      current.onExitAllArgs(site, self, returned, arguments);
    }
  }
}
