package org.otel.agent.core.path;

/**
 * The values available when a selected method exits: {@code $this}, {@code $return} and the
 * arguments {@code $argN}.
 *
 * <p>Implemented by the engine. A frame is only valid during one exit and must never be stored.
 */
public interface ExitFrame {

  /** The receiver ({@code $this}). */
  Object self();

  /** The return value ({@code $return}); {@code null} for void methods. */
  Object returned();

  /** Argument {@code index} ({@code $argN}), or {@code null} if the method has fewer arguments. */
  Object argument(int index);
}
