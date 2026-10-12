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
 *
 * <p>Messages quote text from the rule file (keys, paths, conditions). Every message is therefore
 * made {@linkplain #printable printable} first, so a crafted file cannot forge log lines or send
 * terminal escape sequences.
 */
public final class AgentLog {

  private static final Logger LOGGER = Logger.getLogger("org.otel.agent");
  private static final String PREFIX = "[dynamic-trace-agent] ";
  private static final int MAX_MESSAGE_LENGTH = 2_000;

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

  public void warn(final String message) {
    LOGGER.log(Level.WARNING, PREFIX + printable(message));
  }

  public void warn(final String message, final Throwable cause) {
    LOGGER.log(Level.WARNING, PREFIX + printable(message), cause);
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
    LOGGER.log(Level.INFO, PREFIX + printable(message));
  }

  /** Logs at INFO with a debug marker, only in debug mode. The message is built lazily. */
  public void debug(final Supplier<String> message) {
    if (debugEnabled) {
      LOGGER.log(Level.INFO, PREFIX + "debug: " + printable(message.get()));
    }
  }

  /**
   * The message on one line: control characters, line separators and invisible formatting
   * characters (such as text-direction overrides) are shown as a Unicode escape, and an overlong
   * message is cut off.
   */
  static String printable(final String message) {
    final int length = Math.min(message.length(), MAX_MESSAGE_LENGTH);
    final StringBuilder text = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      final char c = message.charAt(i);
      if (isPrintable(c)) {
        text.append(c);
      } else {
        text.append(String.format("\\u%04x", (int) c));
      }
    }
    return message.length() > length ? text.append("…").toString() : text.toString();
  }

  private static boolean isPrintable(final char c) {
    return switch (Character.getType(c)) {
      case Character.CONTROL,
          Character.FORMAT,
          Character.LINE_SEPARATOR,
          Character.PARAGRAPH_SEPARATOR ->
          false;
      default -> true;
    };
  }
}
