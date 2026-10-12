package org.otel.agent.extension.diag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AgentLogTest {

  private final List<String> messages = new ArrayList<>();
  private final Handler capture =
      new Handler() {
        @Override
        public void publish(final LogRecord record) {
          messages.add(record.getMessage());
        }

        @Override
        public void flush() {}

        @Override
        public void close() {}
      };

  @BeforeEach
  void attach() {
    Logger.getLogger("org.otel.agent").addHandler(capture);
  }

  @AfterEach
  void detach() {
    Logger.getLogger("org.otel.agent").removeHandler(capture);
  }

  @Test
  void debugOffNeverBuildsTheMessage() {
    final int[] built = {0};

    new AgentLog(false)
        .debug(
            () -> {
              built[0]++;
              return "expensive";
            });

    assertEquals(0, built[0]);
    assertEquals(List.of(), messages);
  }

  @Test
  void debugOnLogs() {
    new AgentLog(true).debug(() -> "bound 1 rule(s)");

    assertEquals(List.of("[dynamic-trace-agent] debug: bound 1 rule(s)"), messages);
  }

  @Test
  void warnOnceUntilReset() {
    final AgentLog log = new AgentLog(false);

    log.warnOnce("k", "first");
    log.warnOnce("k", "again");
    log.resetOnce();
    log.warnOnce("k", "after reset");

    assertEquals(
        List.of("[dynamic-trace-agent] first", "[dynamic-trace-agent] after reset"), messages);
  }

  @Test
  void textFromTheRuleFileCannotForgeLogLines() {
    new AgentLog(false).warn("key 'a\nWARNING: fake line\r' and \u001b[31mred\u202e\u2028");

    assertEquals(
        List.of(
            "[dynamic-trace-agent] key 'a\\u000aWARNING: fake line\\u000d' and"
                + " \\u001b[31mred\\u202e\\u2028"),
        messages);
  }

  @Test
  void ordinaryTextIsUnchangedAndOverlongTextIsCut() {
    assertEquals("größe 'x' → ok", AgentLog.printable("größe 'x' → ok"));
    assertEquals(2_001, AgentLog.printable("x".repeat(5_000)).length());
  }
}
