package org.otel.agent.core.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.otel.agent.core.config.ConfigurationException;
import org.otel.agent.core.config.RuleDefinition.Selector;
import org.otel.agent.core.config.XmlConfigurationParser;
import org.otel.agent.core.plan.PlanEntry;
import org.otel.agent.core.plan.TransformationPlan;

class SnapshotCompilerTest {

  private static final String GARAGE =
      """
      <selector><class name="com.example.Garage"/><method name="park"/></selector>
      """;

  private static Snapshot compile(final String xml) throws ConfigurationException {
    return SnapshotCompiler.compile(new XmlConfigurationParser().parse(xml), 7);
  }

  private static String rules(final String body) {
    return """
        <configuration>
          <dynamic>%s</dynamic>
        </configuration>
        """
        .formatted(body);
  }

  @Test
  void referenceConfiguration() throws Exception {
    final Snapshot snapshot =
        compile(Files.readString(Path.of("../config/agent-config.xml"), StandardCharsets.UTF_8));

    assertEquals(7, snapshot.version());
    assertTrue(snapshot.isEnabled());
    assertEquals(6, snapshot.staticAttributes().size());
    assertEquals(
        TransformationPlan.of(List.of(PlanEntry.of(new Selector("com.example.Garage", "park"), 0))),
        snapshot.plan());
    final CompiledRule rule = snapshot.rules().get(0);
    assertTrue(rule.condition().isPresent());
    assertEquals(12, rule.attributes().size());
    assertEquals(0, rule.maxArgumentIndex());
    assertEquals(1, snapshot.selectedRules("park").size());
    assertTrue(snapshot.selectedRules("drive").isEmpty());
  }

  @Test
  void invalidAttributePathRejectsTheDocument() {
    final ConfigurationException exception =
        assertThrows(
            ConfigurationException.class,
            () ->
                compile(
                    rules(
                        """
                        <rule>
                          %s
                          <attribute key="brand" path="$arg0..brand"/>
                        </rule>
                        """
                            .formatted(GARAGE))));

    assertTrue(exception.getMessage().contains("rule #1"));
    assertTrue(exception.getMessage().contains("'brand'"));
  }

  @Test
  void conditionSyntaxErrorDisablesOnlyThatRule() throws Exception {
    final Snapshot snapshot =
        compile(
            rules(
                """
                <rule>
                  %s
                  <when>$arg0.brand &amp;&amp; true</when>
                </rule>
                <rule>
                  <selector><class name="com.example.Car"/><method name="drive"/></selector>
                </rule>
                """
                    .formatted(GARAGE)));

    assertEquals(1, snapshot.rules().size());
    assertEquals(2, snapshot.rules().get(0).definition().position());
    assertEquals(1, snapshot.ignoredRules().size());
    assertTrue(snapshot.ignoredRules().get(0).reason().contains("syntax error"));
    assertEquals(Set.of("com.example.Car"), snapshot.plan().classNames());
  }

  @Test
  void highestArgumentIndexComesFromAttributesAndCondition() throws Exception {
    final Snapshot snapshot =
        compile(
            rules(
                """
                <rule>
                  %s
                  <when>size($arg5.items) gt 0 or $arg1 == 'x'</when>
                  <attribute key="a" path="$arg2.name"/>
                </rule>
                """
                    .formatted(GARAGE)));

    assertEquals(5, snapshot.rules().get(0).maxArgumentIndex());
    assertTrue(snapshot.plan().entries().iterator().next().allArguments());
  }

  @Test
  void indexedArgumentRootCountsForTheArgumentMode() throws Exception {
    final Snapshot snapshot =
        compile(
            rules(
                """
                <rule>
                  %s
                  <when>$arg6[0] != null</when>
                  <attribute key="a" path="$arg4[1].name"/>
                </rule>
                """
                    .formatted(GARAGE)));

    assertEquals(6, snapshot.rules().get(0).maxArgumentIndex());
    assertTrue(snapshot.plan().entries().iterator().next().allArguments());
  }

  @Test
  void protectedRulesStayIgnored() throws Exception {
    final Snapshot snapshot =
        compile(
            rules(
                """
                <rule>
                  <selector><class name="java.util.ArrayList"/><method name="add"/></selector>
                </rule>
                """));

    assertFalse(snapshot.isEnabled());
    assertEquals(1, snapshot.ignoredRules().size());
    assertTrue(snapshot.plan().isEmpty());
  }

  @Test
  void emptyConfigurationIsDisabled() throws Exception {
    final Snapshot snapshot = compile("<configuration/>");

    assertFalse(snapshot.isEnabled());
    assertTrue(snapshot.plan().isEmpty());
  }

  @Test
  void disabledSnapshot() {
    assertFalse(Snapshot.disabled().isEnabled());
    assertEquals(0, Snapshot.disabled().version());
  }
}
