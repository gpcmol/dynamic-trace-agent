package org.otel.agent.core.naming;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class JavaNamesTest {

  @ParameterizedTest
  @ValueSource(strings = {"park", "licensePlate", "_x", "$y", "a1", "ÿ"})
  void validIdentifiers(final String value) {
    assertTrue(JavaNames.isIdentifier(value));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {"1a", "a.b", "a b", "a-b", "a[0]"})
  void invalidIdentifiers(final String value) {
    assertFalse(JavaNames.isIdentifier(value));
  }

  @ParameterizedTest
  @ValueSource(strings = {"Garage", "com.example.Garage", "com.example.Outer$Inner"})
  void validBinaryClassNames(final String value) {
    assertTrue(JavaNames.isBinaryClassName(value));
  }

  @ParameterizedTest
  @NullAndEmptySource
  @ValueSource(strings = {".a", "a.", "a..b", "a.1b", "a/b"})
  void invalidBinaryClassNames(final String value) {
    assertFalse(JavaNames.isBinaryClassName(value));
  }
}
