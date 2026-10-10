package org.otel.agent.extension.diag;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The agent's only logging channel (spec {@code diagnostics}).
 *
 * <ul>
 *   <li>{@link #warn} / {@link #warnOnce}: always on; for configuration problems. {@code warnOnce}
 *       reports each problem key once until {@link #resetOnce()} (called when a new configuration
 *       is published), so the set of remembered keys stays bounded by one configuration.
 *   <li>{@link #debug}: only when OpenTelemetry agent debugging is on ({@code otel.javaagent.debug}
 *       / {@code OTEL_JAVAAGENT_DEBUG}); off, it costs one field read and builds no message.
 * </ul>
 *
 * Never log attribute values: they may contain personal data.
 */
public final class AgentLog {

  private static final Logger LOGGER = Logger.getLogger("org.otel.agent");
  private static final String PREFIX = "[dynamic-trace-agent] ";

  private final boolean debugEnabled;
  private final Set<String> reported = ConcurrentHashMap.newKeySet();

  public AgentLog(final boolean debugEnabled) {
    this.debugEnabled = debugEnabled;
  }

  /** Debug is on when the OTel agent runs in debug mode. */
  public static AgentLog fromEnvironment() {
    final String property = System.getProperty("otel.javaagent.debug");
    final String value = property != null ? property : System.getenv("OTEL_JAVAAGENT_DEBUG");
    return new AgentLog(value != null && value.trim().toLowerCase(Locale.ROOT).equals("true"));
  }

  public boolean debugEnabled() {
    return debugEnabled;
  }

  public void warn(final String message) {
    LOGGER.log(Level.WARNING, PREFIX + message);
  }

  public void warn(final String message, final Throwable cause) {
    LOGGER.log(Level.WARNING, PREFIX + message, cause);
  }

  /** Logs {@code message} only the first time {@code key} is seen since the last reset. */
  public void warnOnce(final String key, final String message) {
    if (reported.add(key)) {
      warn(message);
    }
  }

  /** Forgets reported keys; call when a new configuration becomes active. */
  public void resetOnce() {
    reported.clear();
  }

  public void info(final String message) {
    LOGGER.log(Level.INFO, PREFIX + message);
  }

  /** Logs at INFO with a debug marker, only in debug mode. The message is built lazily. */
  public void debug(final Supplier<String> message) {
    if (debugEnabled) {
      LOGGER.log(Level.INFO, PREFIX + "debug: " + message.get());
    }
  }
}
