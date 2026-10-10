package org.otel.agent.extension.memory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.lang.ref.WeakReference;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.FixedValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.otel.agent.core.config.XmlConfigurationParser;
import org.otel.agent.core.snapshot.SnapshotCompiler;
import org.otel.agent.extension.diag.AgentLog;
import org.otel.agent.extension.engine.EngineAccess;
import org.otel.agent.extension.engine.EnrichmentEngine;
import org.otel.agent.extension.instrument.SiteRegistry;
import org.otel.agent.extension.telemetry.SpanWriter;

/**
 * Spec {@code memory-safety}: an application classloader whose classes were enriched, rebound and
 * reloaded several times must be garbage-collectable afterwards.
 *
 * <p>This exercises every per-class structure the agent creates: the class bindings ({@code
 * ClassValue}), property readers ({@code MethodHandle}s), path-reader inline caches, the per-thread
 * frame slot (the test thread is reused) and the site registry.
 */
@SuppressWarnings("try") // scopes are opened for their side effect
class ClassLoaderUnloadTest {

  private final InMemorySpanExporter exporter = InMemorySpanExporter.create();
  private final SdkTracerProvider provider =
      SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(exporter)).build();
  private final EnrichmentEngine engine =
      new EnrichmentEngine(new SpanWriter(provider.get("test")), new AgentLog(false));

  @AfterEach
  void close() {
    provider.close();
  }

  @Test
  void enrichedApplicationClassLoaderIsCollected() throws Exception {
    final WeakReference<ClassLoader> loader = useApplicationAndForget();

    assertTrue(GarbageCollection.collected(loader), "application classloader was not collected");
  }

  /** Every strong reference to the application lives only in this frame. */
  private WeakReference<ClassLoader> useApplicationAndForget() throws Exception {
    final Class<?> type = applicationClass();
    final Object instance = type.getConstructor().newInstance();
    type.getField("brand").set(instance, "bmw");
    final int site =
        SiteRegistry.siteOf(
            new SiteRegistry.SiteInfo(
                type.getName(), "park", "(Ljava/lang/Object;)Ljava/lang/Object;"));

    for (int version = 1; version <= 5; version++) {
      engine.publish(
          SnapshotCompiler.compile(
              new XmlConfigurationParser()
                  .parse(
                      "<configuration><static><attribute key=\"v\" value=\""
                          + version
                          + "\"/></static><dynamic><rule><selector><class name=\""
                          + type.getName()
                          + "\"/><method name=\"park\"/></selector>"
                          + "<when>$this.brand == 'bmw'</when>"
                          + "<attribute key=\"brand\" path=\"$this.brand\"/>"
                          + "<attribute key=\"other\" path=\"$arg0.brand\"/>"
                          + "</rule></dynamic></configuration>"),
              version));
      final Span span = provider.get("test").spanBuilder("call").startSpan();
      try (Scope scope = span.makeCurrent()) {
        engine.onExit(site, instance, instance, instance, null, null, null);
      } finally {
        span.end();
      }
    }
    assertEquals(
        "bmw",
        exporter.getFinishedSpanItems().get(4).getAttributes().get(AttributeKey.stringKey("brand")),
        "the class was really enriched");
    EngineAccess.disable(engine);
    return new WeakReference<>(type.getClassLoader());
  }

  /** A class in its own classloader: {@code public String brand; getBrand(); park(Object)}. */
  private static Class<?> applicationClass() throws NoSuchMethodException {
    return new ByteBuddy()
        .subclass(Object.class)
        .name("com.example.leak.Sample")
        .defineField("brand", String.class, Visibility.PUBLIC)
        .defineMethod("getBrand", String.class, Visibility.PUBLIC)
        .intercept(FieldAccessor.ofField("brand"))
        .defineMethod("park", Object.class, Visibility.PUBLIC)
        .withParameters(Object.class)
        .intercept(FixedValue.self())
        .make()
        .load(ClassLoader.getPlatformClassLoader(), ClassLoadingStrategy.Default.WRAPPER)
        .getLoaded();
  }
}
