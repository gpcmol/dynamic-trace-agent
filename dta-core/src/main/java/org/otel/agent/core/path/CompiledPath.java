package org.otel.agent.core.path;

import java.util.List;
import java.util.Objects;

/**
 * A parsed path such as {@code $arg0.passengers[1].name} or {@code $return[0].color}. Pure data:
 * holds no {@code Class} and can live in a configuration snapshot. Reading values is done by a
 * {@link PathReader}.
 *
 * @param root where the path starts
 * @param rootIndex the element selected from the root value ({@code $return[0]}), or {@link
 *     PathSegment#NO_INDEX}
 * @param segments the properties after the root
 */
public record CompiledPath(PathRoot root, int rootIndex, List<PathSegment> segments) {

  public CompiledPath {
    Objects.requireNonNull(root, "root");
    if (rootIndex < PathSegment.NO_INDEX) {
      throw new IllegalArgumentException("rootIndex must be >= 0 or NO_INDEX");
    }
    segments = List.copyOf(segments);
  }

  /** A path without root index. */
  public CompiledPath(final PathRoot root, final List<PathSegment> segments) {
    this(root, PathSegment.NO_INDEX, segments);
  }

  public boolean hasRootIndex() {
    return rootIndex != PathSegment.NO_INDEX;
  }

  @Override
  public String toString() {
    final StringBuilder text = new StringBuilder(root.toString());
    if (hasRootIndex()) {
      text.append('[').append(rootIndex).append(']');
    }
    for (final PathSegment segment : segments) {
      text.append('.').append(segment);
    }
    return text.toString();
  }
}
