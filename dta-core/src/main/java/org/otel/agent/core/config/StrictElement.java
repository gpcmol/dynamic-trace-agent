package org.otel.agent.core.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * A DOM element seen through the configuration's strict rules: no namespaces, only known child
 * elements and only known XML attributes. Every violation becomes a {@link ConfigurationException}
 * whose message names the element.
 */
final class StrictElement {

  private final Element element;
  private final String location;

  private StrictElement(final Element element, final String location) {
    this.element = element;
    this.location = location;
  }

  static StrictElement root(final Element element, final String expectedName)
      throws ConfigurationException {
    final StrictElement root = new StrictElement(element, "<" + expectedName + ">");
    if (!expectedName.equals(element.getLocalName()) || element.getNamespaceURI() != null) {
      throw new ConfigurationException(
          "root element must be <" + expectedName + "> without namespace");
    }
    return root;
  }

  String location() {
    return location;
  }

  /** Fails when the element has XML attributes other than {@code allowed}. */
  StrictElement allowAttributes(final String... allowed) throws ConfigurationException {
    final Set<String> names = Set.of(allowed);
    final NamedNodeMap attributes = element.getAttributes();
    for (int i = 0; i < attributes.getLength(); i++) {
      final Node attribute = attributes.item(i);
      if (attribute.getNamespaceURI() != null || !names.contains(attribute.getNodeName())) {
        throw new ConfigurationException(
            location + ": unknown XML attribute '" + attribute.getNodeName() + "'");
      }
    }
    return this;
  }

  /** Fails when the element has child elements other than {@code allowed}. */
  StrictElement allowChildren(final String... allowed) throws ConfigurationException {
    final Set<String> names = Set.of(allowed);
    for (final Element child : childElements()) {
      if (child.getNamespaceURI() != null || !names.contains(child.getLocalName())) {
        throw new ConfigurationException(
            location + ": unknown element <" + child.getNodeName() + ">");
      }
    }
    return this;
  }

  /** All children with the given name, in document order. */
  List<StrictElement> children(final String name) {
    final List<StrictElement> result = new ArrayList<>();
    int position = 0;
    for (final Element child : childElements()) {
      if (name.equals(child.getLocalName())) {
        position++;
        result.add(new StrictElement(child, location + "/<" + name + ">#" + position));
      }
    }
    return result;
  }

  /** The single child with the given name, or {@code null}; fails when there are several. */
  StrictElement optionalChild(final String name) throws ConfigurationException {
    final List<StrictElement> matches = children(name);
    if (matches.size() > 1) {
      throw new ConfigurationException(location + ": <" + name + "> may occur only once");
    }
    return matches.isEmpty() ? null : matches.get(0).relocated(location + "/<" + name + ">");
  }

  /** The single child with the given name; fails when it is missing or repeated. */
  StrictElement requiredChild(final String name) throws ConfigurationException {
    final StrictElement child = optionalChild(name);
    if (child == null) {
      throw new ConfigurationException(location + ": <" + name + "> is required");
    }
    return child;
  }

  /** A non-blank XML attribute value, not trimmed. */
  String requiredAttribute(final String name) throws ConfigurationException {
    final String value = element.getAttribute(name);
    if (value.isBlank()) {
      throw new ConfigurationException(location + ": attribute '" + name + "' is required");
    }
    return value;
  }

  String trimmedText() {
    return element.getTextContent().trim();
  }

  private StrictElement relocated(final String newLocation) {
    return new StrictElement(element, newLocation);
  }

  private List<Element> childElements() {
    final List<Element> result = new ArrayList<>();
    final NodeList nodes = element.getChildNodes();
    for (int i = 0; i < nodes.getLength(); i++) {
      if (nodes.item(i) instanceof Element child) {
        result.add(child);
      }
    }
    return result;
  }
}
