package org.otel.agent.core.path;

/** A path does not follow the path syntax. Rejects the configuration document. */
public final class InvalidPathException extends Exception {

  private static final long serialVersionUID = 1L;

  public InvalidPathException(final String path, final String reason) {
    super("invalid path '" + path + "': " + reason);
  }
}
