package org.otel.agent.extension.reload;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Settings from the environment (spec {@code runtime-reload}).
 *
 * @param configFile {@code DYNAMIC_TRACE_CONFIG_FILE}; empty when not set (agent inactive)
 * @param interval {@code DYNAMIC_TRACE_CONFIG_RELOAD_INTERVAL} in seconds; a positive integer,
 *     anything else means the default of 5 seconds
 */
public record ReloadSettings(Optional<Path> configFile, Duration interval) {

  public static final String CONFIG_FILE_VARIABLE = "DYNAMIC_TRACE_CONFIG_FILE";
  public static final String INTERVAL_VARIABLE = "DYNAMIC_TRACE_CONFIG_RELOAD_INTERVAL";
  public static final Duration DEFAULT_INTERVAL = Duration.ofSeconds(5);

  public static ReloadSettings fromEnvironment() {
    return from(System.getenv());
  }

  static ReloadSettings from(final Map<String, String> environment) {
    final String file = environment.get(CONFIG_FILE_VARIABLE);
    final Optional<Path> configFile =
        file == null || file.isBlank() ? Optional.empty() : Optional.of(Path.of(file.trim()));
    return new ReloadSettings(configFile, interval(environment.get(INTERVAL_VARIABLE)));
  }

  private static Duration interval(final String value) {
    if (value == null) {
      return DEFAULT_INTERVAL;
    }
    try {
      final long seconds = Long.parseLong(value.trim());
      return seconds > 0 ? Duration.ofSeconds(seconds) : DEFAULT_INTERVAL;
    } catch (NumberFormatException notANumber) {
      return DEFAULT_INTERVAL;
    }
  }
}
