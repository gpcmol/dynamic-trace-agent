package org.otel.agent.core.path;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Finds the reader for a property of a class: public {@code getX()}, then public {@code isX()}
 * returning {@code boolean}/{@code Boolean}, then the accessor {@code x()} of a record component,
 * then a public instance field {@code x}.
 *
 * <p>Readers are {@link MethodHandle}s of type {@code (Object)Object}, discovered once per (class,
 * property) and cached <em>on the class itself</em> via a {@link ClassValue}. That direction of
 * reference (application class → reader) lets application classloaders be garbage-collected; see
 * {@code docs/03-classloaders.md} §7 and spike S5. Maps, static members and arbitrary methods are
 * not supported.
 *
 * <p>Security: {@link ForbiddenTypes} have no properties, and {@code class} ({@code getClass()}) is
 * never a property.
 */
public final class PropertyAccessors {

  private static final MethodType READER_TYPE = MethodType.methodType(Object.class, Object.class);

  private static final ClassValue<ConcurrentHashMap<String, Optional<MethodHandle>>> READERS =
      new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<String, Optional<MethodHandle>> computeValue(
            final Class<?> type) {
          return new ConcurrentHashMap<>();
        }
      };

  private PropertyAccessors() {}

  /** The reader for {@code property} on {@code type}, or {@code null} if there is none. */
  public static MethodHandle reader(final Class<?> type, final String property) {
    return READERS.get(type).computeIfAbsent(property, name -> discover(type, name)).orElse(null);
  }

  /**
   * The declared type a property read returns, for static checks; empty if the property does not
   * exist. Uses the same discovery order as {@link #reader}.
   */
  public static Optional<Class<?>> propertyType(final Class<?> type, final String property) {
    if (!isReadable(type, property)) {
      return Optional.empty();
    }
    final Method getter = getter(type, property);
    if (getter != null) {
      return Optional.of(getter.getReturnType());
    }
    final Field field = publicInstanceField(type, property);
    return field == null ? Optional.empty() : Optional.of(field.getType());
  }

  /**
   * Why a property cannot be read, for the warning of an ignored rule: it does not exist, or the
   * type is one a path may never read from.
   */
  public static String describeMissing(final Class<?> type, final String property) {
    return isReadable(type, property)
        ? "'" + property + "' is not a property of " + type.getName()
        : "'" + property + "' cannot be read from " + type.getName() + " (protected type)";
  }

  private static boolean isReadable(final Class<?> type, final String property) {
    return !ForbiddenTypes.CLASS_PROPERTY.equals(property) && !ForbiddenTypes.contains(type);
  }

  private static Optional<MethodHandle> discover(final Class<?> type, final String property) {
    if (!isReadable(type, property)) {
      return Optional.empty();
    }
    final Method getter = getter(type, property);
    if (getter != null) {
      return toReader(() -> unreflect(getter));
    }
    final Field field = publicInstanceField(type, property);
    if (field != null) {
      return toReader(() -> unreflectGetter(field));
    }
    return Optional.empty();
  }

  private static Method getter(final Class<?> type, final String property) {
    final String suffix = Character.toUpperCase(property.charAt(0)) + property.substring(1);
    final Method get = publicNoArgInstanceMethod(type, "get" + suffix);
    if (get != null && get.getReturnType() != void.class) {
      return get;
    }
    final Method is = publicNoArgInstanceMethod(type, "is" + suffix);
    if (is != null
        && (is.getReturnType() == boolean.class || is.getReturnType() == Boolean.class)) {
      return is;
    }
    return recordComponentAccessor(type, property);
  }

  private static Method recordComponentAccessor(final Class<?> type, final String property) {
    if (!type.isRecord()) {
      return null;
    }
    for (final RecordComponent component : type.getRecordComponents()) {
      if (component.getName().equals(property)) {
        return component.getAccessor();
      }
    }
    return null;
  }

  private static Method publicNoArgInstanceMethod(final Class<?> type, final String name) {
    try {
      final Method method = type.getMethod(name);
      return Modifier.isStatic(method.getModifiers()) ? null : method;
    } catch (NoSuchMethodException | SecurityException | LinkageError absent) {
      return null;
    }
  }

  private static Field publicInstanceField(final Class<?> type, final String name) {
    try {
      final Field field = type.getField(name);
      return Modifier.isStatic(field.getModifiers()) ? null : field;
    } catch (NoSuchFieldException | SecurityException | LinkageError absent) {
      return null;
    }
  }

  private interface HandleSource {
    MethodHandle get() throws IllegalAccessException;
  }

  private static Optional<MethodHandle> toReader(final HandleSource source) {
    try {
      return Optional.of(source.get().asType(READER_TYPE));
    } catch (IllegalAccessException | RuntimeException inaccessible) {
      return Optional.empty();
    }
  }

  /**
   * Public members of non-public classes (or inherited from one) are not reachable through the
   * public lookup; for those the member is made accessible when the module allows it.
   */
  private static MethodHandle unreflect(final Method method) throws IllegalAccessException {
    try {
      return MethodHandles.publicLookup().unreflect(method);
    } catch (IllegalAccessException notPublic) {
      if (!method.trySetAccessible()) {
        throw notPublic;
      }
      return MethodHandles.lookup().unreflect(method);
    }
  }

  private static MethodHandle unreflectGetter(final Field field) throws IllegalAccessException {
    try {
      return MethodHandles.publicLookup().unreflectGetter(field);
    } catch (IllegalAccessException notPublic) {
      if (!field.trySetAccessible()) {
        throw notPublic;
      }
      return MethodHandles.lookup().unreflectGetter(field);
    }
  }
}
