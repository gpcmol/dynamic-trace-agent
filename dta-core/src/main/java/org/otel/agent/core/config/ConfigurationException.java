package org.otel.agent.core.config;

/**
 * The configuration document as a whole is invalid. The agent keeps its previous configuration.
 *
 * <p>Problems that only affect one rule are not exceptions: they are reported as {@link
 * IgnoredRule}s.
 */
public final class ConfigurationException extends Exception {

  private static final long serialVersionUID = 1L;

  public ConfigurationException(final String message) {
    super(message);
  }

  public ConfigurationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
