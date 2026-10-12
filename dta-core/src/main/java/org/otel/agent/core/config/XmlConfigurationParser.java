package org.otel.agent.core.config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.otel.agent.core.config.ConfigDocument.IgnoredRule;
import org.otel.agent.core.config.ConfigDocument.StaticAttribute;
import org.otel.agent.core.config.RuleDefinition.DynamicAttribute;
import org.otel.agent.core.config.RuleDefinition.Selector;
import org.otel.agent.core.naming.JavaNames;

/**
 * Reads the XML configuration into a {@link ConfigDocument}.
 *
 * <p>Document-level problems (malformed XML, unknown elements, duplicate keys, …) throw a {@link
 * ConfigurationException}: the whole document is rejected. Rules that are valid but not allowed (a
 * selector on a protected namespace) are returned as {@link IgnoredRule}s. Paths and conditions are
 * kept as text here; they are compiled later. The format is described in {@code
 * docs/06-config-xml.md}.
 *
 * <p>The document is operator input and is treated as data only: it is parsed by {@link
 * SecureXmlReader}, every name is validated, and nothing in it is ever executed.
 */
public final class XmlConfigurationParser {

  /** Maximum length of an attribute key. */
  public static final int MAX_KEY_LENGTH = 255;

  public ConfigDocument parse(final String xml) throws ConfigurationException {
    final StrictElement root =
        StrictElement.root(SecureXmlReader.readRootElement(xml), "configuration")
            .allowAttributes()
            .allowChildren("static", "dynamic");

    final Set<String> keys = new HashSet<>();
    final List<StaticAttribute> staticAttributes =
        parseStaticSection(root.optionalChild("static"), keys);
    final List<RuleDefinition> rules = parseDynamicSection(root.optionalChild("dynamic"), keys);
    return withProtectedRulesIgnored(staticAttributes, rules);
  }

  private static List<StaticAttribute> parseStaticSection(
      final StrictElement section, final Set<String> keys) throws ConfigurationException {
    if (section == null) {
      return List.of();
    }
    section.allowAttributes().allowChildren("attribute");
    final List<StaticAttribute> attributes = new ArrayList<>();
    for (final StrictElement element : section.children("attribute")) {
      element.allowAttributes("key", "value").allowChildren();
      final String key = element.requiredAttribute("key");
      registerKey(key, keys, element.location());
      attributes.add(new StaticAttribute(key, element.requiredAttribute("value")));
    }
    return attributes;
  }

  private static List<RuleDefinition> parseDynamicSection(
      final StrictElement section, final Set<String> keys) throws ConfigurationException {
    if (section == null) {
      return List.of();
    }
    section.allowAttributes().allowChildren("rule");

    final Set<Selector> selectorsInDocument = new HashSet<>();
    final List<RuleDefinition> rules = new ArrayList<>();
    final List<StrictElement> ruleElements = section.children("rule");
    for (int i = 0; i < ruleElements.size(); i++) {
      final RuleDefinition rule = parseRule(i + 1, ruleElements.get(i), keys);
      for (final Selector selector : rule.selectors()) {
        if (!selectorsInDocument.add(selector)) {
          throw new ConfigurationException(
              "rule #" + rule.position() + ": " + selector + " is selected more than once");
        }
      }
      rules.add(rule);
    }
    return rules;
  }

  private static RuleDefinition parseRule(
      final int position, final StrictElement element, final Set<String> keys)
      throws ConfigurationException {
    element.allowAttributes().allowChildren("selector", "when", "attribute");
    final String location = "rule #" + position;

    final List<Selector> selectors = new ArrayList<>();
    for (final StrictElement selector : element.children("selector")) {
      selectors.add(parseSelector(selector, location));
    }
    if (selectors.isEmpty()) {
      throw new ConfigurationException(location + ": at least one <selector> is required");
    }

    final List<DynamicAttribute> attributes = new ArrayList<>();
    for (final StrictElement attribute : element.children("attribute")) {
      attribute.allowAttributes("key", "path").allowChildren();
      final String key = attribute.requiredAttribute("key");
      registerKey(key, keys, location);
      attributes.add(new DynamicAttribute(key, attribute.requiredAttribute("path")));
    }

    return new RuleDefinition(position, selectors, parseWhen(element), attributes);
  }

  private static Selector parseSelector(final StrictElement selector, final String location)
      throws ConfigurationException {
    selector.allowAttributes().allowChildren("class", "method");
    final StrictElement classElement = selector.requiredChild("class");
    final StrictElement methodElement = selector.requiredChild("method");
    classElement.allowAttributes("name").allowChildren();
    methodElement.allowAttributes("name").allowChildren();

    final String className = classElement.requiredAttribute("name");
    if (!JavaNames.isBinaryClassName(className)) {
      throw new ConfigurationException(
          location + ": '" + className + "' is not a valid fully qualified class name");
    }
    final String methodName = methodElement.requiredAttribute("name");
    if (!JavaNames.isIdentifier(methodName)) {
      throw new ConfigurationException(
          location + ": '" + methodName + "' is not a valid method name");
    }
    return new Selector(className, methodName);
  }

  /** A blank or absent {@code <when>} means: unconditional. */
  private static Optional<String> parseWhen(final StrictElement rule)
      throws ConfigurationException {
    final StrictElement when = rule.optionalChild("when");
    if (when == null) {
      return Optional.empty();
    }
    when.allowAttributes().allowChildren();
    final String expression = when.trimmedText();
    return expression.isEmpty() ? Optional.empty() : Optional.of(expression);
  }

  private static void registerKey(final String key, final Set<String> keys, final String location)
      throws ConfigurationException {
    if (key.length() > MAX_KEY_LENGTH) {
      throw new ConfigurationException(
          location + ": attribute key is longer than " + MAX_KEY_LENGTH + " characters");
    }
    if (key.chars().anyMatch(Character::isISOControl)) {
      throw new ConfigurationException(
          location + ": an attribute key must not contain control characters");
    }
    if (!keys.add(key)) {
      throw new ConfigurationException(
          location + ": attribute key '" + key + "' is used more than once in the document");
    }
  }

  private static ConfigDocument withProtectedRulesIgnored(
      final List<StaticAttribute> staticAttributes, final List<RuleDefinition> rules) {
    final List<RuleDefinition> accepted = new ArrayList<>();
    final List<IgnoredRule> ignored = new ArrayList<>();
    for (final RuleDefinition rule : rules) {
      final Optional<String> reason = protectedSelectorReason(rule);
      if (reason.isPresent()) {
        ignored.add(new IgnoredRule(rule, reason.get()));
      } else {
        accepted.add(rule);
      }
    }
    return new ConfigDocument(staticAttributes, accepted, ignored);
  }

  private static Optional<String> protectedSelectorReason(final RuleDefinition rule) {
    for (final Selector selector : rule.selectors()) {
      final Optional<String> prefix = ProtectedNamespaces.protectedPrefixOf(selector.className());
      if (prefix.isPresent()) {
        return Optional.of(
            selector
                + " is in the protected namespace '"
                + prefix.get()
                + "*' (JDK, OpenTelemetry, Byte Buddy and the agent itself cannot be selected)");
      }
    }
    return Optional.empty();
  }
}
