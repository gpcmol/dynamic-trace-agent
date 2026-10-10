package org.otel.agent.gateway;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class EnrichmentGatewayTest {

  @AfterEach
  void uninstall() {
    EnrichmentGateway.install(null);
  }

  @Test
  void withoutEngineEveryCallIsANoOp() {
    assertDoesNotThrow(
        () -> EnrichmentGateway.onExit(1, new Object(), null, null, null, null, null));
    assertDoesNotThrow(() -> EnrichmentGateway.onExitAllArgs(1, new Object(), null, new Object[0]));
  }

  @Test
  void forwardsToTheInstalledEngine() {
    final List<String> calls = new ArrayList<>();
    EnrichmentGateway.install(
        new ExitHandler() {
          @Override
          public void onExit(
              final int site,
              final Object self,
              final Object returned,
              final Object a0,
              final Object a1,
              final Object a2,
              final Object a3) {
            calls.add("fixed " + site + " " + a0);
          }

          @Override
          public void onExitAllArgs(
              final int site, final Object self, final Object returned, final Object[] arguments) {
            calls.add("all " + site + " " + arguments.length);
          }
        });

    EnrichmentGateway.onExit(7, "self", "ret", "a0", null, null, null);
    EnrichmentGateway.onExitAllArgs(8, "self", "ret", new Object[5]);

    assertEquals(List.of("fixed 7 a0", "all 8 5"), calls);
  }
}
