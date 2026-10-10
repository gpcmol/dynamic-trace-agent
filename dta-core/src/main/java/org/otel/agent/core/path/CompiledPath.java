package org.otel.agent.core.path;

import java.util.List;
import java.util.Objects;

/**
 * A parsed path such as {@code $arg0.passengers[1].name}. Pure data: holds no {@code Class} and can
 * live in a configuration snapshot. Reading values is done by a {@link PathReader}.
 */
public record CompiledPath(PathRoot root, List<PathSegment> segments) {

  public CompiledPath {
    Objects.requireNonNull(root, "root");
    segments = List.copyOf(segments);
  }

  @Override
  public String toString() {
    final StringBuilder text = new StringBuilder(root.toString());
    for (final PathSegment segment : segments) {
      text.append('.').append(segment);
    }
    return text.toString();
  }
}
