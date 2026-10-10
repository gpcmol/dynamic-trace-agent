package org.otel.agent.extension.memory;

import java.lang.management.ManagementFactory;
import java.lang.ref.WeakReference;

/** Test helpers to observe garbage collection. */
final class GarbageCollection {

  private GarbageCollection() {}

  /** Runs GC until the referent is gone or the attempts are exhausted. */
  static boolean collected(final WeakReference<?> reference) throws InterruptedException {
    for (int attempt = 0; attempt < 50 && reference.get() != null; attempt++) {
      System.gc();
      Thread.sleep(20);
    }
    return reference.get() == null;
  }

  /** Used heap after a few full collections, in bytes. */
  static long usedHeapAfterGc() throws InterruptedException {
    for (int i = 0; i < 5; i++) {
      System.gc();
      Thread.sleep(20);
    }
    return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
  }
}
