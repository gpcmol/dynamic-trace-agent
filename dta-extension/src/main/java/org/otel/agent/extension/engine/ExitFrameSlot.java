package org.otel.agent.extension.engine;

import org.otel.agent.core.path.ExitFrame;

/**
 * A reusable, per-thread {@link ExitFrame}: filling it costs a few field writes instead of an
 * allocation per call (spec {@code hot-path-performance}).
 *
 * <p>{@link #tryAcquire()} doubles as the re-entrancy guard: while a thread is enriching, a nested
 * selected method on the same thread (e.g. a getter that is itself selected) finds the slot in use
 * and is skipped.
 *
 * <p>Memory: the slot lives in a {@code ThreadLocal} on application threads. It is an agent type
 * (loaded by the never-unloaded extension classloader) and every reference to an application object
 * is cleared in {@link #release()}, so it cannot keep an application classloader alive.
 */
final class ExitFrameSlot implements ExitFrame {

  private static final ThreadLocal<ExitFrameSlot> SLOTS =
      ThreadLocal.withInitial(ExitFrameSlot::new);

  private boolean inUse;
  private Object self;
  private Object returned;
  private Object a0;
  private Object a1;
  private Object a2;
  private Object a3;
  private Object[] arguments;

  /** The slot of the current thread if it is free, otherwise {@code null} (nested call). */
  static ExitFrameSlot tryAcquire() {
    final ExitFrameSlot slot = SLOTS.get();
    if (slot.inUse) {
      return null;
    }
    slot.inUse = true;
    return slot;
  }

  ExitFrameSlot fill(
      final Object self,
      final Object returned,
      final Object a0,
      final Object a1,
      final Object a2,
      final Object a3) {
    this.self = self;
    this.returned = returned;
    this.a0 = a0;
    this.a1 = a1;
    this.a2 = a2;
    this.a3 = a3;
    this.arguments = null;
    return this;
  }

  ExitFrameSlot fill(final Object self, final Object returned, final Object[] arguments) {
    this.self = self;
    this.returned = returned;
    this.arguments = arguments;
    return this;
  }

  /** Drops every application reference and frees the slot. */
  void release() {
    self = null;
    returned = null;
    a0 = null;
    a1 = null;
    a2 = null;
    a3 = null;
    arguments = null;
    inUse = false;
  }

  @Override
  public Object self() {
    return self;
  }

  @Override
  public Object returned() {
    return returned;
  }

  @Override
  public Object argument(final int index) {
    if (arguments != null) {
      return index < arguments.length ? arguments[index] : null;
    }
    return switch (index) {
      case 0 -> a0;
      case 1 -> a1;
      case 2 -> a2;
      case 3 -> a3;
      default -> null;
    };
  }
}
