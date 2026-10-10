package org.otel.agent.gateway;

/**
 * Receives method exits from advice woven into application classes.
 *
 * <p>Implemented by the engine in the extension classloader and registered with {@link
 * EnrichmentGateway#install(ExitHandler)}. Signatures use JDK types only, because this interface
 * lives in the bootstrap classloader (see {@code docs/03-classloaders.md}).
 *
 * <p>Implementations must never throw and must not retain any of the passed objects after
 * returning.
 */
public interface ExitHandler {

  /**
   * Exit of a selected method whose rules use at most {@code $arg3}.
   *
   * @param site constant identifying the instrumented method, baked into the advice
   * @param self the receiver ({@code $this})
   * @param returned the return value ({@code $return}), {@code null} for void methods
   * @param a0 first argument or {@code null}
   * @param a1 second argument or {@code null}
   * @param a2 third argument or {@code null}
   * @param a3 fourth argument or {@code null}
   */
  void onExit(int site, Object self, Object returned, Object a0, Object a1, Object a2, Object a3);

  /** Exit of a selected method whose rules use {@code $arg4} or higher. */
  void onExitAllArgs(int site, Object self, Object returned, Object[] arguments);
}
