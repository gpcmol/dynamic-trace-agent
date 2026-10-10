package org.otel.agent.core.dsl;

import java.util.Objects;

/**
 * A {@code <when>} expression cannot be used. The rule that owns it is disabled and the problem is
 * reported once, with its {@link Category}.
 */
public final class ConditionException extends Exception {

  private static final long serialVersionUID = 1L;

  /** The failure categories named in the documentation and in warnings. */
  public enum Category {
    SYNTAX_ERROR("syntax error"),
    TYPE_MISMATCH("type mismatch"),
    UNKNOWN_FUNCTION("unknown function"),
    UNKNOWN_PROPERTY("unknown property"),
    ERASED_COLLECTION_TYPE("erased collection type"),
    MAP_UNSUPPORTED("map unsupported"),
    WRONG_ARITY("wrong arity");

    private final String label;

    Category(final String label) {
      this.label = label;
    }

    public String label() {
      return label;
    }
  }

  private final Category category;

  public ConditionException(final Category category, final String detail) {
    super(Objects.requireNonNull(category, "category").label() + ": " + detail);
    this.category = category;
  }

  public Category category() {
    return category;
  }
}
