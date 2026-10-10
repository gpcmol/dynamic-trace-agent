package org.otel.agent.extension.telemetry;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import java.util.List;
import org.otel.agent.core.config.ConfigDocument.StaticAttribute;
import org.otel.agent.core.value.ValueList;

/** Writes attributes on spans and creates the fallback span (spec {@code attribute-enrichment}). */
public final class SpanWriter {

  /** Name of the span created when a selected method exits outside any trace. */
  public static final String FALLBACK_SPAN_NAME = "dynamic-trace.enrichment";

  /** Instrumentation scope of the fallback span. */
  public static final String TRACER_NAME = "dynamic-trace-agent";

  private final Tracer tracer;

  public SpanWriter(final Tracer tracer) {
    this.tracer = tracer;
  }

  /** Builds the static attributes of a configuration once; written with one call per event. */
  public static Attributes staticAttributes(final List<StaticAttribute> attributes) {
    final var builder = Attributes.builder();
    for (final StaticAttribute attribute : attributes) {
      builder.put(attribute.key(), attribute.value());
    }
    return builder.build();
  }

  /** Starts the {@code INTERNAL} fallback span; the caller must end it. */
  public Span startFallbackSpan() {
    return tracer.spanBuilder(FALLBACK_SPAN_NAME).setSpanKind(SpanKind.INTERNAL).startSpan();
  }

  /**
   * Writes a value produced by {@code AttributeValues.convert}: {@code String}, {@code Long},
   * {@code Double}, {@code Boolean} or {@link ValueList}. Anything else (including {@code null}) is
   * not written.
   */
  public void write(final Span span, final AttributeKeySet keys, final Object value) {
    if (value instanceof String text) {
      span.setAttribute(keys.string, text);
    } else if (value instanceof Long number) {
      span.setAttribute(keys.longValue, number);
    } else if (value instanceof Double number) {
      span.setAttribute(keys.doubleValue, number);
    } else if (value instanceof Boolean flag) {
      span.setAttribute(keys.booleanValue, flag);
    } else if (value instanceof ValueList list) {
      writeList(span, keys, list);
    }
  }

  @SuppressWarnings("unchecked")
  private static void writeList(final Span span, final AttributeKeySet keys, final ValueList list) {
    final List<?> values = list.values();
    final Class<?> type = list.elementType();
    if (type == String.class) {
      span.setAttribute(keys.stringArray, (List<String>) values);
    } else if (type == Long.class) {
      span.setAttribute(keys.longArray, (List<Long>) values);
    } else if (type == Double.class) {
      span.setAttribute(keys.doubleArray, (List<Double>) values);
    } else if (type == Boolean.class) {
      span.setAttribute(keys.booleanArray, (List<Boolean>) values);
    }
  }
}
