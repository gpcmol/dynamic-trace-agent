package org.otel.agent.extension.bootstrap;

import com.google.auto.service.AutoService;
import io.opentelemetry.javaagent.tooling.bootstrap.BootstrapPackagesBuilder;
import io.opentelemetry.javaagent.tooling.bootstrap.BootstrapPackagesConfigurer;

/**
 * Makes classloaders that do not delegate to bootstrap by default (OSGi, JBoss Modules) still find
 * the gateway: OpenTelemetry's boot-delegation instrumentation forwards these packages to the
 * bootstrap classloader ({@code docs/03-classloaders.md} §9, spike S6).
 *
 * <p>{@code BootstrapPackagesConfigurer} is an internal OTel SPI; this class is its only user.
 */
@AutoService(BootstrapPackagesConfigurer.class)
public final class GatewayBootDelegation implements BootstrapPackagesConfigurer {

  static final String GATEWAY_PACKAGE = "org.otel.agent.gateway";

  @Override
  public void configure(final BootstrapPackagesBuilder builder) {
    builder.add(GATEWAY_PACKAGE);
  }
}
