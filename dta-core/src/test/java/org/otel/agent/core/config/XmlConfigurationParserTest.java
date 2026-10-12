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
    return "<configuration><dynamic>%s</dynamic></configuration>".formatted(rules);
  }

  private static String rule(final String body) {
    return dynamic("<rule>%s</rule>".formatted(body));
  }

  private static final String GARAGE_SELECTOR =
      """
      <selector><class name="com.example.Garage"/><method name="park"/></selector>
      """;

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

      assertTrue(document.rules().isEmpty());
      assertTrue(document.staticAttributes().isEmpty());
    }

    @Test
    void markerOnlyRuleWithoutAttributes() throws Exception {
      final ConfigDocument document =
          parse(
              """
              <configuration>
                <static><attribute key="debug" value="true"/></static>
                <dynamic>
                  <rule>
                    %s
                    <when>$arg0.brand == 'bmw'</when>
                  </rule>
                </dynamic>
              </configuration>
              """
                  .formatted(GARAGE_SELECTOR));

      assertTrue(document.rules().get(0).attributes().isEmpty());
    }

    @Test
    void selectorsKeepDocumentOrder() throws Exception {
      final RuleDefinition rule =
          parse(
                  rule(
                      """
                      %s
                      <selector><class name="com.example.Car"/><method name="drive"/></selector>
                      """
                          .formatted(GARAGE_SELECTOR)))
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
                      """
                      <selector><class name="com.example.Outer$Inner"/><method name="run"/></selector>
                      """))
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
"""
<configuration><static><attribute key=" team" value=" a "/></static></configuration>""")
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
                  """
                  <configuration>
                    <static><attribute key="%s" value="v"/></static>
                  </configuration>
                  """
                      .formatted(key))
              .staticAttributes()
              .get(0)
              .key());
    }

    @Test
    void xmlCommentsAreAllowed() throws Exception {
      assertTrue(parse("<configuration><!-- nothing yet --></configuration>").rules().isEmpty());
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
          """
          <!DOCTYPE configuration [<!ENTITY leak SYSTEM "%s">]>
          <configuration>
            <static><attribute key="x" value="&leak;"/></static>
          </configuration>
          """
              .formatted(secret.toUri());

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
      rejected(
"""
<configuration xmlns="urn:x"/>""");
    }

    @Test
    void attributeOnRoot() {
      assertTrue(
          rejected(
"""
<configuration version="1"/>""")
              .getMessage()
              .contains("version"));
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
      rejected(
"""
<configuration><static><attr key="a" value="b"/></static></configuration>""");
    }

    @Test
    void unknownAttributeOnStaticAttribute() {
      assertTrue(
          rejected(
                  """
                  <configuration>
                    <static><attribute key="a" value="b" type="x"/></static>
                  </configuration>
                  """)
              .getMessage()
              .contains("type"));
    }

    @Test
    void namespacedAttribute() {
      rejected(
          """
          <configuration xmlns:x="urn:x">
            <static><attribute key="a" value="b" x:y="z"/></static>
          </configuration>
          """);
    }

    @Test
    void unknownElementInRule() {
      rejected(rule(GARAGE_SELECTOR + "<condition>true</condition>"));
    }

    @Test
    void ruleWithoutSelector() {
      assertTrue(
          rejected(
                  rule(
"""
<attribute key="a" path="$this.x"/>"""))
              .getMessage()
              .contains("<selector>"));
    }

    @Test
    void selectorWithoutMethod() {
      rejected(
          rule(
"""
<selector><class name="com.example.Garage"/></selector>"""));
    }

    @Test
    void selectorWithTwoClasses() {
      rejected(
          rule(
              """
              <selector><class name="a.B"/><class name="a.C"/><method name="m"/></selector>
              """));
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
          """
          <configuration>
            <static><attribute key=" " value="b"/></static>
          </configuration>
          """);
    }

    @Test
    void missingStaticValue() {
      assertTrue(
          rejected(
"""
<configuration><static><attribute key="a"/></static></configuration>""")
              .getMessage()
              .contains("value"));
    }

    @Test
    void missingPath() {
      rejected(
          rule(
              """
              %s
              <attribute key="a"/>
              """
                  .formatted(GARAGE_SELECTOR)));
    }

    @Test
    void blankClassName() {
      rejected(
          rule(
"""
<selector><class name=""/><method name="park"/></selector>"""));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {"Garage.", ".Garage", "com..Garage", "com.example.Garage#park", "1com.X"})
    void invalidClassName(final String className) {
      assertTrue(
          rejected(
                  rule(
                      """
                      <selector><class name="%s"/><method name="park"/></selector>
                      """
                          .formatted(className)))
              .getMessage()
              .contains("class name"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"park()", "pa rk", "9park", "park.now"})
    void invalidMethodName(final String methodName) {
      assertTrue(
          rejected(
                  rule(
                      """
                      <selector><class name="com.example.Garage"/><method name="%s"/></selector>
                      """
                          .formatted(methodName)))
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
              """
              <configuration>
                <static><attribute key="brand" value="x"/></static>
                <dynamic>
                  <rule>
                    %s
                    <attribute key="brand" path="$arg0.brand"/>
                  </rule>
                </dynamic>
              </configuration>
              """
                  .formatted(GARAGE_SELECTOR));

      assertTrue(exception.getMessage().contains("'brand'"));
    }

    @Test
    void duplicateKeyAcrossRules() {
      rejected(
          dynamic(
              """
              <rule>
                %s
                <attribute key="k" path="$this.a"/>
              </rule>
              <rule>
                <selector><class name="com.example.Car"/><method name="drive"/></selector>
                <attribute key="k" path="$this.b"/>
              </rule>
              """
                  .formatted(GARAGE_SELECTOR)));
    }

    @Test
    void keyLongerThanMaximum() {
      final String key = "k".repeat(XmlConfigurationParser.MAX_KEY_LENGTH + 1);

      rejected(
          """
          <configuration>
            <static><attribute key="%s" value="v"/></static>
          </configuration>
          """
              .formatted(key));
    }

    @ParameterizedTest
    @ValueSource(strings = {"line&#10;break", "tab&#9;", "c1&#x85;", "esc&#x9b;[31m"})
    void keyWithControlCharacter(final String key) {
      assertTrue(
          rejected(
                  rule(GARAGE_SELECTOR + "<attribute key=\"%s\" path=\"$this.a\"/>".formatted(key)))
              .getMessage()
              .contains("control characters"));
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
      rejected(
          dynamic("<rule>%s</rule><rule>%s</rule>".formatted(GARAGE_SELECTOR, GARAGE_SELECTOR)));
    }
  }

  @Nested
  class DynamicSection {

    @Test
    void onlyRulesAreAllowedInDynamic() {
      assertTrue(
          rejected(
                  dynamic(
"""
<attribute key="brand" path="$arg0.brand"/>"""))
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
          "org.w3c.dom.Node",
          "org.xml.sax.XMLReader",
          "io.opentelemetry.api.trace.Span",
          "net.bytebuddy.ByteBuddy",
          "org.otel.agent.gateway.EnrichmentGateway"
        })
    void protectedSelectorDisablesOnlyThatRule(final String className) throws Exception {
      final ConfigDocument document =
          parse(
              dynamic(
                  """
                  <rule>
                    <selector><class name="%s"/><method name="size"/></selector>
                  </rule>
                  <rule>
                    %s
                  </rule>
                  """
                      .formatted(className, GARAGE_SELECTOR)));

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
                  """
                  %s
                  <selector><class name="java.util.ArrayList"/><method name="add"/></selector>
                  """
                      .formatted(GARAGE_SELECTOR)));

      assertTrue(document.rules().isEmpty());
      assertEquals(1, document.ignoredRules().size());
    }

    @Test
    void similarLookingPackageIsNotProtected() throws Exception {
      final ConfigDocument document =
          parse(
              rule(
"""
<selector><class name="javaapp.Main"/><method name="run"/></selector>"""));

      assertEquals(1, document.rules().size());
    }
  }

  /** The document is data: nothing in it can reach outside the parser or be executed. */
  @Nested
  class Injection {

    @ParameterizedTest
    @ValueSource(
        strings = {
          "<!DOCTYPE configuration [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
              + "<configuration><static><attribute key=\"k\" value=\"&x;\"/></static></configuration>",
          "<!DOCTYPE configuration [<!ENTITY a \"aaaa\"><!ENTITY b \"&a;&a;&a;&a;\">]>"
              + "<configuration/>",
          "<!DOCTYPE configuration SYSTEM \"http://127.0.0.1:1/x.dtd\"><configuration/>",
          "<configuration xmlns:xi=\"http://www.w3.org/2001/XInclude\">"
              + "<xi:include href=\"file:///etc/passwd\" parse=\"text\"/></configuration>",
          "<?xml-stylesheet href=\"file:///etc/passwd\"?><configuration"
              + " xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\""
              + " xsi:schemaLocation=\"http://127.0.0.1:1/x.xsd\"/>",
          "<configuration><static><attribute key=\"k\" value=\"&undefined;\"/></static>"
              + "</configuration>"
        })
    void entitiesIncludesAndSchemasAreRejected(final String xml) {
      rejected(xml);
    }

    @Test
    void classNamesAndMethodNamesAreNamesOnly() {
      rejected(
          dynamic(
              "<rule><selector><class name=\"java.lang.Runtime.getRuntime()\"/>"
                  + "<method name=\"park\"/></selector></rule>"));
      rejected(
          dynamic(
              "<rule><selector><class name=\"com.example.Garage\"/>"
                  + "<method name=\"park();exit\"/></selector></rule>"));
    }

    @Test
    void markupInsideAValueStaysText() throws Exception {
      final ConfigDocument document =
          parse(
              "<configuration><static>"
                  + "<attribute key=\"k\" value=\"&lt;rule&gt;${jndi:ldap://x} $(id) `id`\"/>"
                  + "</static></configuration>");

      assertEquals(
          new StaticAttribute("k", "<rule>${jndi:ldap://x} $(id) `id`"),
          document.staticAttributes().get(0));
      assertTrue(document.rules().isEmpty());
    }
  }
}
