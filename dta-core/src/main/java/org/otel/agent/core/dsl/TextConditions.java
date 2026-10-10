package org.otel.agent.core.dsl;

import java.util.Collection;
import org.otel.agent.core.path.ExitFrame;

/**
 * {@code contains}, {@code icontains}, {@code like}, {@code ilike}. A {@code null} haystack or
 * needle is {@code false}.
 *
 * <ul>
 *   <li>{@code contains(text, part)}: substring. {@code contains(collection, element)}: element
 *       equality.
 *   <li>{@code icontains(text, other)}: whole-string, case-insensitive equality (not substring).
 *       {@code icontains(collection, text)}: any element equal ignoring case.
 *   <li>{@code like}/{@code ilike}: SQL wildcards, see {@link LikePattern}.
 * </ul>
 *
 * When the haystack type is unknown at bind time ({@code Object}) the variant is chosen per call.
 */
final class TextConditions {

  private TextConditions() {}

  record Contains(ValueNode haystack, ValueNode needle, boolean ignoreCase) implements Condition {
    @Override
    public boolean test(final ExitFrame frame) {
      final Object hay = haystack.value(frame);
      final Object wanted = needle.value(frame);
      if (hay == null || wanted == null) {
        return false;
      }
      if (hay instanceof String text) {
        return ignoreCase
            ? wanted instanceof String other && text.equalsIgnoreCase(other)
            : wanted instanceof String part && text.contains(part);
      }
      if (hay instanceof Collection<?> collection) {
        return ignoreCase ? containsIgnoringCase(collection, wanted) : collection.contains(wanted);
      }
      return false;
    }

    private static boolean containsIgnoringCase(
        final Collection<?> collection, final Object wanted) {
      if (!(wanted instanceof String text)) {
        return false;
      }
      for (final Object element : collection) {
        if (element instanceof String candidate && candidate.equalsIgnoreCase(text)) {
          return true;
        }
      }
      return false;
    }
  }

  /** {@code like}/{@code ilike} with a literal pattern, compiled once. */
  record LikeLiteral(ValueNode value, LikePattern pattern) implements Condition {
    @Override
    public boolean test(final ExitFrame frame) {
      return value.value(frame) instanceof String text && pattern.matches(text);
    }
  }

  /** {@code like}/{@code ilike} whose pattern comes from a path; compiled per call. */
  record LikeDynamic(ValueNode value, ValueNode pattern, boolean ignoreCase) implements Condition {
    @Override
    public boolean test(final ExitFrame frame) {
      return value.value(frame) instanceof String text
          && pattern.value(frame) instanceof String patternText
          && LikePattern.compile(patternText, ignoreCase).matches(text);
    }
  }
}
