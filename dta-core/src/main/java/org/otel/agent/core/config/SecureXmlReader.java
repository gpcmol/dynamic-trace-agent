package org.otel.agent.core.config;

import java.io.IOException;
import java.io.StringReader;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.w3c.dom.Element;
import org.xml.sax.ErrorHandler;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Parses XML with the JDK parser in a locked-down mode: no DOCTYPE, no external entities, DTDs or
 * schemas. The configuration never needs any of those, and allowing them would let a crafted file
 * read local files or reach the network (XXE).
 */
final class SecureXmlReader {

  private SecureXmlReader() {}

  static Element readRootElement(final String xml) throws ConfigurationException {
    if (xml == null || xml.isBlank()) {
      throw new ConfigurationException("configuration file is empty");
    }
    try {
      final DocumentBuilder builder = newFactory().newDocumentBuilder();
      builder.setErrorHandler(SilentErrorHandler.INSTANCE);
      return builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
    } catch (SAXParseException malformed) {
      throw new ConfigurationException(
          "malformed XML at line "
              + malformed.getLineNumber()
              + ", column "
              + malformed.getColumnNumber()
              + ": "
              + malformed.getMessage(),
          malformed);
    } catch (SAXException | IOException | ParserConfigurationException failure) {
      throw new ConfigurationException("cannot parse XML: " + failure.getMessage(), failure);
    }
  }

  private static DocumentBuilderFactory newFactory() throws ParserConfigurationException {
    final DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory;
  }

  /** Turns parser warnings/errors into exceptions instead of printing them to stderr. */
  private enum SilentErrorHandler implements ErrorHandler {
    INSTANCE;

    @Override
    public void warning(final SAXParseException exception) {
      // Warnings do not make the document invalid.
    }

    @Override
    public void error(final SAXParseException exception) throws SAXException {
      throw exception;
    }

    @Override
    public void fatalError(final SAXParseException exception) throws SAXException {
      throw exception;
    }
  }
}
