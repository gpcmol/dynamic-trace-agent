package org.otel.agent.extension.reload;

import java.util.function.Consumer;
import org.otel.agent.core.config.ConfigDocument.IgnoredRule;
import org.otel.agent.core.config.ConfigurationException;
import org.otel.agent.core.config.XmlConfigurationParser;
import org.otel.agent.core.plan.PlanDiff;
import org.otel.agent.core.plan.TransformationPlan;
import org.otel.agent.core.snapshot.Snapshot;
import org.otel.agent.core.snapshot.SnapshotCompiler;
import org.otel.agent.extension.diag.AgentLog;

/**
 * Turns configuration text into the active configuration (spec {@code runtime-reload}).
 *
 * <pre>
 *   text ─ parse + compile ─┬─ invalid ─▶ keep current snapshot, warn
 *                           └─ valid ───▶ 1. publish snapshot  (engine)
 *                                         2. publish plan      (matchers)
 *                                         3. retransform classes in the plan diff
 * </pre>
 *
 * Publishing before retransforming is correct in both directions: added rules are ready before
 * their advice exists; removed rules are inactive before their advice is stripped (D7).
 */
public final class ReloadCoordinator implements ConfigFileWatcher.Listener {

  /** Receives the snapshot to publish and the plan to give the matchers. */
  public interface Target {
    Snapshot activeSnapshot();

    void publish(Snapshot snapshot);

    void publishPlan(TransformationPlan plan);
  }

  private final XmlConfigurationParser parser = new XmlConfigurationParser();
  private final Target target;
  private final Retransformer retransformer;
  private final AgentLog log;
  private long nextVersion = 1;
  private TransformationPlan planToCheckForLoadedClasses;

  public ReloadCoordinator(
      final Target target, final Retransformer retransformer, final AgentLog log) {
    this.target = target;
    this.retransformer = retransformer;
    this.log = log;
  }

  @Override
  public synchronized void contentChanged(final String content) {
    final Snapshot next;
    try {
      next = SnapshotCompiler.compile(parser.parse(content), nextVersion);
    } catch (ConfigurationException invalid) {
      log.warn("configuration rejected, keeping the current one: " + invalid.getMessage());
      return;
    }
    nextVersion++;
    activate(next, "loaded configuration " + next.version());
  }

  @Override
  public synchronized void fileMissing() {
    if (target.activeSnapshot().isEnabled()) {
      activate(Snapshot.disabled(), "disabled: configuration file removed");
    }
  }

  /**
   * Warns once per selector whose class is not loaded (yet) or has no public method of the selected
   * name. Called by the poller one interval after a load, so the application had time to load its
   * classes.
   */
  public synchronized void reportSelectorProblems() {
    final TransformationPlan plan = planToCheckForLoadedClasses;
    planToCheckForLoadedClasses = null;
    if (plan != null) {
      retransformer.selectorProblems(plan).forEach(log::warnOnce);
    }
  }

  private void activate(final Snapshot next, final String description) {
    final Snapshot previous = target.activeSnapshot();
    log.resetOnce();
    report(next, log::warn);

    final long start = System.nanoTime();
    target.publish(next);
    target.publishPlan(next.plan());
    final PlanDiff diff = PlanDiff.between(previous.plan(), next.plan());
    final int retransformed = retransformer.apply(diff);
    planToCheckForLoadedClasses = next.plan();

    log.info(
        description
            + ": "
            + next.rules().size()
            + " rule(s) active, "
            + next.ignoredRules().size()
            + " ignored, "
            + retransformed
            + " class(es) retransformed");
    log.debug(
        () ->
            "plan "
                + next.plan().fingerprint()
                + ", added "
                + diff.added()
                + ", removed "
                + diff.removed()
                + ", took "
                + (System.nanoTime() - start) / 1_000_000
                + " ms");
  }

  private static void report(final Snapshot snapshot, final Consumer<String> warn) {
    for (final IgnoredRule ignored : snapshot.ignoredRules()) {
      warn.accept(ignored.rule().describe() + " is ignored: " + ignored.reason());
    }
  }
}
