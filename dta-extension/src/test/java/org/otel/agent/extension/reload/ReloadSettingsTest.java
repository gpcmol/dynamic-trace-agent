package org.otel.agent.extension.reload;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ReloadSettingsTest {

  @Test
  void noFileMeansInactive() {
    assertEquals(Optional.empty(), ReloadSettings.from(Map.of()).configFile());
    assertEquals(
        Optional.empty(),
        ReloadSettings.from(Map.of(ReloadSettings.CONFIG_FILE_VARIABLE, "  ")).configFile());
  }

  @Test
  void fileAndInterval() {
    final ReloadSettings settings =
        ReloadSettings.from(
            Map.of(
                ReloadSettings.CONFIG_FILE_VARIABLE, " /etc/dynamic-trace/agent-config.xml ",
                ReloadSettings.INTERVAL_VARIABLE, "30"));

    assertEquals(
        Optional.of(Path.of("/etc/dynamic-trace/agent-config.xml")), settings.configFile());
    assertEquals(Duration.ofSeconds(30), settings.interval());
  }

  @ParameterizedTest
  @ValueSource(strings = {"0", "-5", "abc", "", "1.5"})
  void invalidIntervalFallsBackToFiveSeconds(final String value) {
    assertEquals(
        Duration.ofSeconds(5),
        ReloadSettings.from(Map.of(ReloadSettings.INTERVAL_VARIABLE, value)).interval());
  }
}
