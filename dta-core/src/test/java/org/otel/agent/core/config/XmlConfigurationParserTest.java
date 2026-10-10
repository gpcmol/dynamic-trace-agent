package org.otel.agent.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.otel.agent.core.config.ConfigDocument.StaticAttribute;
import org.otel.agent.core.config.RuleDefinition.DynamicAttribute;
import org.otel.agent.core.config.RuleDefinition.Selector;

class XmlConfigurationParserTest {

  /** The reference configuration shared by the demo, the benchmark and the Kubernetes example. */
  private static final Path REFERENCE_CONFIG = Path.of("../config/agent-config.xml");

  private final XmlConfigurationParser parser = new XmlConfigurationParser();

  private ConfigDocument parse(final String xml) throws ConfigurationException {
    return parser.parse(xml);
  }

  private ConfigurationException rejected(final String xml) {
    return assertThrows(ConfigurationException.class, () -> parser.parse(xml));
  }

  private static String dynamic(final String rules) {
    return "<configuration><dynamic>" + rules + "</dynamic></configuration>";
  }

  private static String rule(final String body) {
    return dynamic("<rule>" + body + "</rule>");
  }

  private static final String GARAGE_SELECTOR =
      "<selector><class name=\"com.example.Garage\"/><method name=\"park\"/></selector>";

  @Nested
  class ValidDocuments {

    @Test
    void referenceConfiguration() throws Exception {
      final ConfigDocument document =
          parse(Files.readString(REFERENCE_CONFIG, StandardCharsets.UTF_8));

      assertEquals(6, document.staticAttributes().size());
      assertEquals(new StaticAttribute("debug", "true"), document.staticAttributes().get(0));
      assertEquals(1, document.rules().size());
      final RuleDefinition rule = document.rules().get(0);
      assertEquals(List.of(new Selector("com.example.Garage", "park")), rule.selectors());
      assertTrue(rule.when().orElseThrow().startsWith("$arg0.brand in ["));
      assertEquals(12, rule.attributes().size());
      assertEquals(
          new DynamicAttribute("passengers", "$arg0.passengers[1].name"), rule.attributes().get(9));
      assertTrue(document.ignoredRules().isEmpty());
    }

    @Test
    void emptyConfigurationMeansDisable() throws Exception {
      final ConfigDocument document = parse("<configuration/>");

      assertTrue(document.isEmpty());
      assertTrue(document.staticAttributes().isEmpty());
    }

    @Test
    void markerOnlyRuleWithoutAttributes() throws Exception {
      final ConfigDocument document =
          parse(
              "<configuration><static><attribute key=\"debug\" value=\"true\"/></static>"
                  + "<dynamic><rule>"
                  + GARAGE_SELECTOR
                  + "<when>$arg0.brand == 'bmw'</when></rule></dynamic></configuration>");

      assertTrue(document.rules().get(0).attributes().isEmpty());
    }

    @Test
    void selectorsKeepDocumentOrder() throws Exception {
      final RuleDefinition rule =
          parse(
                  rule(
                      GARAGE_SELECTOR
                          + "<selector><class name=\"com.example.Car\"/>"
                          + "<method name=\"drive\"/></selector>"))
              .rules()
              .get(0);

      assertEquals(
          List.of(
              new Selector("com.example.Garage", "park"), new Selector("com.example.Car", "drive")),
          rule.selectors());
      assertEquals("rule #1 (com.example.Garage#park, com.example.Car#drive)", rule.describe());
    }

    @Test
    void nestedClassBinaryNameIsAccepted() throws Exception {
      final RuleDefinition rule =
          parse(
                  rule(
                      "<selector><class name=\"com.example.Outer$Inner\"/>"
                          + "<method name=\"run\"/></selector>"))
              .rules()
              .get(0);

      assertEquals("com.example.Outer$Inner", rule.selectors().get(0).className());
    }

    @Test
    void whenIsTrimmedAndBlankWhenMeansUnconditional() throws Exception {
      assertEquals(
          Optional.of("$arg0.mileage gt 0"),
          parse(rule(GARAGE_SELECTOR + "<when>\n  $arg0.mileage gt 0\n</when>"))
              .rules()
              .get(0)
              .when());
      assertEquals(
          Optional.empty(), parse(rule(GARAGE_SELECTOR + "<when>  </when>")).rules().get(0).when());
      assertEquals(Optional.empty(), parse(rule(GARAGE_SELECTOR)).rules().get(0).when());
    }

    @Test
    void keysAndValuesAreNotTrimmed() throws Exception {
      final StaticAttribute attribute =
          parse(
                  "<configuration><static><attribute key=\" team\" value=\" a \"/></static></configuration>")
              .staticAttributes()
              .get(0);

      assertEquals(new StaticAttribute(" team", " a "), attribute);
    }

    @Test
    void keyOfMaximumLengthIsAccepted() throws Exception {
      final String key = "k".repeat(XmlConfigurationParser.MAX_KEY_LENGTH);

      assertEquals(
          key,
          parse(
                  "<configuration><static><attribute key=\""
                      + key
                      + "\" value=\"v\"/></static></configuration>")
              .staticAttributes()
              .get(0)
              .key());
    }

    @Test
    void xmlCommentsAreAllowed() throws Exception {
      assertTrue(parse("<configuration><!-- nothing yet --></configuration>").isEmpty());
    }
  }

  @Nested
  class MalformedInput {

    @ParameterizedTest
    @ValueSource(strings = {"", "   ", "\n"})
    void emptyFileIsRejected(final String content) {
      assertTrue(rejected(content).getMessage().contains("empty"));
    }

    @Test
    void malformedXmlReportsLine() {
      assertTrue(
          rejected("<configuration>\n<static>\n</configuration>").getMessage().contains("line"));
    }

    @Test
    void doctypeIsRejected() {
      rejected("<!DOCTYPE configuration><configuration/>");
    }

    @Test
    void externalEntityIsNeverRead(@TempDir final Path directory) throws IOException {
      final Path secret = Files.writeString(directory.resolve("secret.txt"), "TOP-SECRET");
      final String xml =
          "<!DOCTYPE configuration [<!ENTITY leak SYSTEM \""
              + secret.toUri()
              + "\">]><configuration><static><attribute key=\"x\" value=\"&leak;\"/>"
              + "</static></configuration>";

      final ConfigurationException exception = rejected(xml);

      assertFalse(exception.getMessage().contains("TOP-SECRET"));
    }
  }

  @Nested
  class StructureRules {

    @Test
    void wrongRootElement() {
      assertTrue(rejected("<config/>").getMessage().contains("<configuration>"));
    }

    @Test
    void namespacedRootElement() {
      rejected("<configuration xmlns=\"urn:x\"/>");
    }

    @Test
    void attributeOnRoot() {
      assertTrue(rejected("<configuration version=\"1\"/>").getMessage().contains("version"));
    }

    @Test
    void unknownSection() {
      assertTrue(
          rejected("<configuration><extra/></configuration>").getMessage().contains("<extra>"));
    }

    @Test
    void duplicateSection() {
      assertTrue(
          rejected("<configuration><static/><static/></configuration>")
              .getMessage()
              .contains("only once"));
    }

    @Test
    void unknownElementInStatic() {
      rejected("<configuration><static><attr key=\"a\" value=\"b\"/></static></configuration>");
    }

    @Test
    void unknownAttributeOnStaticAttribute() {
      assertTrue(
          rejected(
                  "<configuration><static><attribute key=\"a\" value=\"b\" type=\"x\"/>"
                      + "</static></configuration>")
              .getMessage()
              .contains("type"));
    }

    @Test
    void namespacedAttribute() {
      rejected(
          "<configuration xmlns:x=\"urn:x\"><static><attribute key=\"a\" value=\"b\" x:y=\"z\"/>"
              + "</static></configuration>");
    }

    @Test
    void unknownElementInRule() {
      rejected(rule(GARAGE_SELECTOR + "<condition>true</condition>"));
    }

    @Test
    void ruleWithoutSelector() {
      assertTrue(
          rejected(rule("<attribute key=\"a\" path=\"$this.x\"/>"))
              .getMessage()
              .contains("<selector>"));
    }

    @Test
    void selectorWithoutMethod() {
      rejected(rule("<selector><class name=\"com.example.Garage\"/></selector>"));
    }

    @Test
    void selectorWithTwoClasses() {
      rejected(
          rule(
              "<selector><class name=\"a.B\"/><class name=\"a.C\"/><method name=\"m\"/></selector>"));
    }

    @Test
    void twoWhenElements() {
      rejected(rule(GARAGE_SELECTOR + "<when>true</when><when>false</when>"));
    }

    @Test
    void elementInsideWhen() {
      rejected(rule(GARAGE_SELECTOR + "<when><b>true</b></when>"));
    }
  }

  @Nested
  class RequiredValues {

    @Test
    void blankStaticKey() {
      rejected(
          "<configuration><static><attribute key=\" \" value=\"b\"/></static></configuration>");
    }

    @Test
    void missingStaticValue() {
      assertTrue(
          rejected("<configuration><static><attribute key=\"a\"/></static></configuration>")
              .getMessage()
              .contains("value"));
    }

    @Test
    void missingPath() {
      rejected(rule(GARAGE_SELECTOR + "<attribute key=\"a\"/>"));
    }

    @Test
    void blankClassName() {
      rejected(rule("<selector><class name=\"\"/><method name=\"park\"/></selector>"));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"Garage.", ".Garage", "com..Garage", "com.example.Garage#park", "1com.X"})
    void invalidClassName(final String className) {
      assertTrue(
          rejected(
                  rule(
                      "<selector><class name=\""
                          + className
                          + "\"/><method name=\"park\"/></selector>"))
              .getMessage()
              .contains("class name"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"park()", "pa rk", "9park", "park.now"})
    void invalidMethodName(final String methodName) {
      assertTrue(
          rejected(
                  rule(
                      "<selector><class name=\"com.example.Garage\"/><method name=\""
                          + methodName
                          + "\"/></selector>"))
              .getMessage()
              .contains("method name"));
    }
  }

  @Nested
  class Uniqueness {

    @Test
    void duplicateKeyAcrossStaticAndDynamic() {
      final ConfigurationException exception =
          rejected(
              "<configuration><static><attribute key=\"brand\" value=\"x\"/></static><dynamic><rule>"
                  + GARAGE_SELECTOR
                  + "<attribute key=\"brand\" path=\"$arg0.brand\"/></rule></dynamic></configuration>");

      assertTrue(exception.getMessage().contains("'brand'"));
    }

    @Test
    void duplicateKeyAcrossRules() {
      rejected(
          dynamic(
              "<rule>"
                  + GARAGE_SELECTOR
                  + "<attribute key=\"k\" path=\"$this.a\"/></rule><rule>"
                  + "<selector><class name=\"com.example.Car\"/><method name=\"drive\"/></selector>"
                  + "<attribute key=\"k\" path=\"$this.b\"/></rule>"));
    }

    @Test
    void keyLongerThanMaximum() {
      final String key = "k".repeat(XmlConfigurationParser.MAX_KEY_LENGTH + 1);

      rejected(
          "<configuration><static><attribute key=\""
              + key
              + "\" value=\"v\"/></static></configuration>");
    }

    @Test
    void sameSelectorTwiceInOneRule() {
      assertTrue(
          rejected(rule(GARAGE_SELECTOR + GARAGE_SELECTOR))
              .getMessage()
              .contains("com.example.Garage#park"));
    }

    @Test
    void sameSelectorInTwoRules() {
      rejected(dynamic("<rule>" + GARAGE_SELECTOR + "</rule><rule>" + GARAGE_SELECTOR + "</rule>"));
    }
  }

  @Nested
  class DynamicSection {

    @Test
    void onlyRulesAreAllowedInDynamic() {
      assertTrue(
          rejected(dynamic("<attribute key=\"brand\" path=\"$arg0.brand\"/>"))
              .getMessage()
              .contains("unknown element <attribute>"));
    }
  }

  @Nested
  class ProtectedNamespacesRules {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "java.util.ArrayList",
          "javax.sql.DataSource",
          "jdk.internal.Foo",
          "sun.misc.Unsafe",
          "com.sun.net.httpserver.HttpServer",
          "io.opentelemetry.api.trace.Span",
          "net.bytebuddy.ByteBuddy",
          "org.otel.agent.gateway.EnrichmentGateway"
        })
    void protectedSelectorDisablesOnlyThatRule(final String className) throws Exception {
      final ConfigDocument document =
          parse(
              dynamic(
                  "<rule><selector><class name=\""
                      + className
                      + "\"/><method name=\"size\"/></selector></rule><rule>"
                      + GARAGE_SELECTOR
                      + "</rule>"));

      assertEquals(1, document.rules().size());
      assertEquals(2, document.rules().get(0).position());
      assertEquals(1, document.ignoredRules().size());
      assertTrue(document.ignoredRules().get(0).reason().contains("protected namespace"));
    }

    @Test
    void oneProtectedSelectorDisablesTheWholeRule() throws Exception {
      final ConfigDocument document =
          parse(
              rule(
                  GARAGE_SELECTOR
                      + "<selector><class name=\"java.util.ArrayList\"/>"
                      + "<method name=\"add\"/></selector>"));

      assertTrue(document.rules().isEmpty());
      assertEquals(1, document.ignoredRules().size());
    }

    @Test
    void similarLookingPackageIsNotProtected() throws Exception {
      final ConfigDocument document =
          parse(rule("<selector><class name=\"javaapp.Main\"/><method name=\"run\"/></selector>"));

      assertEquals(1, document.rules().size());
    }
  }
}
