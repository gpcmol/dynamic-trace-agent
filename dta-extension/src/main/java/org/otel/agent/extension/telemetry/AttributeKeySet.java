package org.otel.agent.extension.telemetry;

import io.opentelemetry.api.common.AttributeKey;
import java.util.List;

/**
 * All typed OpenTelemetry keys for one attribute name. The value type is only known per call (a
 * getter may return a {@code Long} today and a {@code String} for another subclass), so every type
 * is prepared once, when a configuration is published — never during a call.
 */
public final class AttributeKeySet {

  final AttributeKey<String> string;
  final AttributeKey<Long> longValue;
  final AttributeKey<Double> doubleValue;
  final AttributeKey<Boolean> booleanValue;
  final AttributeKey<List<String>> stringArray;
  final AttributeKey<List<Long>> longArray;
  final AttributeKey<List<Double>> doubleArray;
  final AttributeKey<List<Boolean>> booleanArray;

  private AttributeKeySet(final String name) {
    this.string = AttributeKey.stringKey(name);
    this.longValue = AttributeKey.longKey(name);
    this.doubleValue = AttributeKey.doubleKey(name);
    this.booleanValue = AttributeKey.booleanKey(name);
    this.stringArray = AttributeKey.stringArrayKey(name);
    this.longArray = AttributeKey.longArrayKey(name);
    this.doubleArray = AttributeKey.doubleArrayKey(name);
    this.booleanArray = AttributeKey.booleanArrayKey(name);
  }

  public static AttributeKeySet of(final String name) {
    return new AttributeKeySet(name);
  }

  public String name() {
    return string.getKey();
  }
}
