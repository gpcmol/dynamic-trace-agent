package org.otel.agent.extension.engine;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.otel.agent.core.binding.MethodBinding;
import org.otel.agent.core.binding.RuleBinder;
import org.otel.agent.core.config.ConfigDocument.IgnoredRule;
import org.otel.agent.extension.diag.AgentLog;
import org.otel.agent.extension.instrument.SiteRegistry;
import org.otel.agent.extension.instrument.SiteRegistry.SiteInfo;

/**
 * Per-class cache of {@link MethodEnrichment}s, attached to the application class itself.
 *
 * <p>Memory (spec {@code memory-safety}, {@code docs/03-classloaders.md} §7): there is exactly one
 * {@link ClassValue} for the JVM's lifetime. Its value for a class references that class's
 * bindings, never the other way around, so the agent never keeps an application class alive. Each
 * entry remembers the published configuration it was built for (by identity, never by a number that
 * could repeat); the first call after a reload rebuilds it, which replaces (and releases) the
 * previous entry.
 *
 * <p>Performance: a call reads one {@code ClassValue}, one {@code AtomicReference}, compares a
 * reference and scans a tiny {@code int[]} of the class's sites. Binding happens once per (class,
 * method, configuration).
 */
final class ClassBindings {

  private static final ClassValue<AtomicReference<Entry>> PER_CLASS =
      new ClassValue<>() {
        @Override
        protected AtomicReference<Entry> computeValue(final Class<?> type) {
          return new AtomicReference<>();
        }
      };

  private final AgentLog log;

  ClassBindings(final AgentLog log) {
    this.log = log;
  }

  @SuppressWarnings("ReferenceEquality") // identity of the published configuration
  MethodEnrichment lookup(final Class<?> type, final int site, final EngineState state) {
    final AtomicReference<Entry> holder = PER_CLASS.get(type);
    final Entry entry = holder.get();
    if (entry != null && entry.state == state) {
      final MethodEnrichment known = entry.find(site);
      if (known != null) {
        return known;
      }
    }
    return bindAndRemember(type, site, state, holder);
  }

  @SuppressWarnings("ReferenceEquality") // identity of the published configuration
  private MethodEnrichment bindAndRemember(
      final Class<?> type,
      final int site,
      final EngineState state,
      final AtomicReference<Entry> holder) {
    final MethodEnrichment enrichment = bindSafely(type, site, state);
    while (true) {
      final Entry current = holder.get();
      final Entry base =
          current != null && current.state == state
              ? current
              : new Entry(state, new int[0], new MethodEnrichment[0]);
      final MethodEnrichment raced = base.find(site);
      if (raced != null) {
        return raced;
      }
      if (holder.compareAndSet(current, base.with(site, enrichment))) {
        return enrichment;
      }
    }
  }

  /**
   * A class that cannot be bound (e.g. it refers to a type that is missing at runtime) gets no
   * enrichment for this configuration; the failure is remembered instead of repeated on every call.
   */
  private MethodEnrichment bindSafely(
      final Class<?> type, final int site, final EngineState state) {
    try {
      return bind(type, site, state);
    } catch (RuntimeException | LinkageError failure) {
      log.warnOnce(
          "bind|" + failure,
          "cannot bind rules to " + type.getName() + " (" + failure + "); it is not enriched");
      return MethodEnrichment.NONE;
    }
  }

  private MethodEnrichment bind(final Class<?> type, final int site, final EngineState state) {
    final SiteInfo info = SiteRegistry.info(site);
    final Method method = info == null ? null : MethodResolver.resolve(type, info);
    if (method == null) {
      log.debug(() -> "no method for site " + site + " in " + type.getName());
      return MethodEnrichment.NONE;
    }
    final MethodBinding binding = RuleBinder.bind(state.snapshot(), type, method);
    for (final IgnoredRule problem : binding.problems()) {
      log.warnOnce(
          problem.rule().describe() + '|' + problem.reason(),
          problem.rule().describe()
              + " is ignored for "
              + type.getName()
              + ": "
              + problem.reason());
    }
    log.debug(
        () ->
            "bound "
                + binding.rules().size()
                + " rule(s) to "
                + type.getName()
                + "#"
                + method.getName()
                + " (configuration "
                + state.version()
                + ")");
    return MethodEnrichment.of(binding, state);
  }

  /** Bindings of one class for one published configuration; copy-on-write. */
  private static final class Entry {
    private final EngineState state;
    private final int[] sites;
    private final MethodEnrichment[] enrichments;

    Entry(final EngineState state, final int[] sites, final MethodEnrichment[] enrichments) {
      this.state = state;
      this.sites = sites;
      this.enrichments = enrichments;
    }

    MethodEnrichment find(final int site) {
      for (int i = 0; i < sites.length; i++) {
        if (sites[i] == site) {
          return enrichments[i];
        }
      }
      return null;
    }

    Entry with(final int site, final MethodEnrichment enrichment) {
      final int[] newSites = Arrays.copyOf(sites, sites.length + 1);
      final MethodEnrichment[] newEnrichments = Arrays.copyOf(enrichments, enrichments.length + 1);
      newSites[sites.length] = site;
      newEnrichments[enrichments.length] = enrichment;
      return new Entry(state, newSites, newEnrichments);
    }
  }
}
