package org.otel.agent.extension.instrument;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Assigns a constant {@code site} id to every instrumented method; the id is baked into the advice
 * and lets the engine find the method's rules without comparing strings (decision D6).
 *
 * <p>Ids are stable for a method (same declaring class name, name and descriptor) and are never
 * reused. Only names are stored, never {@code Class} objects. The registry grows with the number of
 * distinct methods ever instrumented in this JVM, which is bounded by the configurations used.
 */
public final class SiteRegistry {

  private static final int CHUNK = 256;

  /** Where a site is: declaring class name, method name and JVM descriptor. */
  public record SiteInfo(String className, String methodName, String descriptor) {
    public SiteInfo {
      Objects.requireNonNull(className, "className");
      Objects.requireNonNull(methodName, "methodName");
      Objects.requireNonNull(descriptor, "descriptor");
    }
  }

  private static final ConcurrentHashMap<SiteInfo, Integer> IDS = new ConcurrentHashMap<>();
  private static final AtomicInteger NEXT = new AtomicInteger();
  private static volatile AtomicReferenceArray<SiteInfo> sites = new AtomicReferenceArray<>(CHUNK);

  private SiteRegistry() {}

  /** The id of a method, assigned on first use (at transform time, never on the hot path). */
  public static int siteOf(final SiteInfo info) {
    return IDS.computeIfAbsent(info, SiteRegistry::register);
  }

  /** The method behind a site id, or {@code null} for an unknown id. */
  public static SiteInfo info(final int site) {
    final AtomicReferenceArray<SiteInfo> current = sites;
    return site >= 0 && site < current.length() ? current.get(site) : null;
  }

  private static synchronized int register(final SiteInfo info) {
    final int site = NEXT.getAndIncrement();
    AtomicReferenceArray<SiteInfo> current = sites;
    if (site >= current.length()) {
      final AtomicReferenceArray<SiteInfo> grown =
          new AtomicReferenceArray<>(current.length() + CHUNK);
      for (int i = 0; i < current.length(); i++) {
        grown.set(i, current.get(i));
      }
      current = grown;
    }
    current.set(site, info);
    sites = current;
    return site;
  }
}
