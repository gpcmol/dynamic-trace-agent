package org.otel.agent.extension.bootstrap;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk;
import org.otel.agent.extension.diag.AgentLog;
import org.otel.agent.extension.reload.ReloadSettings;

/**
 * Entry point: called by the OpenTelemetry agent after it has started, before the application's
 * {@code main} ({@code docs/03-classloaders.md} §6.1).
 *
 * <ol>
 *   <li>install the gateway into the bootstrap classloader;
 *   <li>start the runtime (engine, first configuration load, poller).
 * </ol>
 *
 * Any failure leaves the extension inactive with one warning; the application is never affected.
 * This class must not reference gateway or engine types: they are only usable after step 1.
 */
@AutoService(AgentListener.class)
public final class AgentStartup implements AgentListener {

  @Override
  public void afterAgent(final AutoConfiguredOpenTelemetrySdk sdk) {
    final AgentLog log = AgentLog.fromEnvironment();
    final ReloadSettings settings;
    try {
      settings = ReloadSettings.fromEnvironment();
    } catch (RuntimeException invalid) {
      log.warn(
          "invalid " + ReloadSettings.CONFIG_FILE_VARIABLE + "; enrichment is disabled", invalid);
      return;
    }
    if (settings.configFile().isEmpty()) {
      log.debug(() -> ReloadSettings.CONFIG_FILE_VARIABLE + " is not set: extension inactive");
      return;
    }
    try {
      GatewayInstaller.install();
    } catch (Exception | LinkageError failure) {
      log.warn("cannot install the gateway; enrichment is disabled", failure);
      return;
    }
    try {
      ExtensionRuntime.start(
          sdk.getOpenTelemetrySdk(), InstrumentationAccess.fromOpenTelemetryAgent(), settings, log);
    } catch (Throwable failure) {
      // Nothing may escape into the OpenTelemetry agent's start-up.
      log.warn("cannot start; enrichment is disabled", failure);
    }
  }
}
