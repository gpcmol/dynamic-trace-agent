package org.otel.agent.core.path;

import java.lang.invoke.MethodHandle;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Reads the value of a {@link CompiledPath} for one method exit.
 *
 * <p>Semantics (spec {@code value-path-resolution}):
 *
 * <ul>
 *   <li>A {@code null} or {@code Map} on the way, a missing property or a throwing getter gives
 *       {@code null}. Nothing ever throws.
 *   <li>{@code [i]} selects from an array, a {@code List} or (by iteration) any {@code Iterable};
 *       out of range gives {@code null}.
 *   <li>A property applied to an array or {@code Iterable} is applied to every element; all
 *       non-null results are collected into one flat {@code List}.
 * </ul>
 *
 * <p>Performance: each segment remembers the reader of the last class it saw (a monomorphic inline
 * cache), so a steady-state read is one class comparison plus one {@code MethodHandle} call per
 * segment.
 *
 * <p>Memory: a reader must be owned by the binding of one application class (it lives in that
 * class's {@code ClassValue} entry). The inline cache only remembers classes from the owner's
 * classloader or its parents, so it can never keep a <em>child</em> classloader alive.
 */
public final class PathReader {

  private final CompiledPath path;
  private final SegmentReader[] segments;

  private PathReader(final CompiledPath path, final ClassLoader ownerLoader) {
    this.path = path;
    this.segments = new SegmentReader[path.segments().size()];
    for (int i = 0; i < segments.length; i++) {
      segments[i] = new SegmentReader(path.segments().get(i), ownerLoader);
    }
  }

  /**
   * A reader owned by the binding of {@code owner}.
   *
   * @param owner the application class whose binding keeps this reader
   */
  public static PathReader ownedBy(final CompiledPath path, final Class<?> owner) {
    return new PathReader(path, owner.getClassLoader());
  }

  public CompiledPath path() {
    return path;
  }

  /** The value for this exit, or {@code null}. */
  public Object read(final ExitFrame frame) {
    return readFrom(path.root().select(frame));
  }

  /** The value starting from an explicit root object, or {@code null}. */
  public Object readFrom(final Object root) {
    if (root == null) {
      return null;
    }
    if (segments.length == 0) {
      return root;
    }
    return resolve(root, 0);
  }

  private Object resolve(final Object current, final int position) {
    if (current == null || current instanceof Map<?, ?>) {
      return null;
    }
    final SegmentReader segment = segments[position];
    if (!segment.indexed && isCollectionLike(current)) {
      return resolveEach(current, position);
    }
    final Object value = segment.read(current);
    if (value == null || position == segments.length - 1) {
      return value;
    }
    return resolve(value, position + 1);
  }

  private List<Object> resolveEach(final Object collection, final int position) {
    final List<Object> values = new ArrayList<>();
    if (collection.getClass().isArray()) {
      final int length = Array.getLength(collection);
      for (int i = 0; i < length; i++) {
        addFlattened(values, resolve(Array.get(collection, i), position));
      }
    } else {
      for (final Object element : (Iterable<?>) collection) {
        addFlattened(values, resolve(element, position));
      }
    }
    return values;
  }

  private static void addFlattened(final List<Object> values, final Object value) {
    if (value == null) {
      return;
    }
    if (value instanceof Collection<?> nested) {
      for (final Object element : nested) {
        if (element != null) {
          values.add(element);
        }
      }
    } else if (value.getClass().isArray()) {
      final int length = Array.getLength(value);
      for (int i = 0; i < length; i++) {
        final Object element = Array.get(value, i);
        if (element != null) {
          values.add(element);
        }
      }
    } else {
      values.add(value);
    }
  }

  private static boolean isCollectionLike(final Object value) {
    return value instanceof Iterable<?> || value.getClass().isArray();
  }

  /** One segment with a monomorphic inline cache of (class → reader). */
  private static final class SegmentReader {
    private final String property;
    private final int index;
    private final boolean indexed;
    private final ClassLoader ownerLoader;

    /**
     * Last (class, reader) pair. Immutable record behind a plain field: a racy read sees either an
     * old or a new complete pair, both correct.
     */
    private Cached cached;

    SegmentReader(final PathSegment segment, final ClassLoader ownerLoader) {
      this.property = segment.property();
      this.index = segment.index();
      this.indexed = segment.isIndexed();
      this.ownerLoader = ownerLoader;
    }

    Object read(final Object target) {
      final MethodHandle reader = readerFor(target.getClass());
      if (reader == null) {
        return null;
      }
      final Object value;
      try {
        value = (Object) reader.invokeExact(target);
      } catch (Throwable getterFailure) {
        return null;
      }
      return indexed ? select(value, index) : value;
    }

    private MethodHandle readerFor(final Class<?> type) {
      final Cached current = cached;
      if (current != null && current.type == type) {
        return current.reader;
      }
      final MethodHandle reader = PropertyAccessors.reader(type, property);
      if (isSameOrParentLoader(type.getClassLoader(), ownerLoader)) {
        cached = new Cached(type, reader);
      }
      return reader;
    }

    private static Object select(final Object value, final int index) {
      if (value == null) {
        return null;
      }
      if (value.getClass().isArray()) {
        return index < Array.getLength(value) ? Array.get(value, index) : null;
      }
      if (value instanceof List<?> list) {
        return index < list.size() ? list.get(index) : null;
      }
      if (value instanceof Iterable<?> iterable) {
        int position = 0;
        for (final Object element : iterable) {
          if (position++ == index) {
            return element;
          }
        }
      }
      return null;
    }

    /**
     * {@code true} if {@code candidate} is {@code owner} or one of its parents (bootstrap = null).
     */
    private static boolean isSameOrParentLoader(
        final ClassLoader candidate, final ClassLoader owner) {
      if (candidate == null) {
        return true;
      }
      for (ClassLoader loader = owner; loader != null; loader = loader.getParent()) {
        if (loader == candidate) {
          return true;
        }
      }
      return false;
    }

    private record Cached(Class<?> type, MethodHandle reader) {}
  }
}
