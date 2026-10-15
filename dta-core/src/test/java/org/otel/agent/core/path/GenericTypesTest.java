package org.otel.agent.core.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class GenericTypesTest {

  public record Tree(String color) {}

  public static final class Trees extends ArrayList<Tree> {
    private static final long serialVersionUID = 1L;
  }

  /** An {@code Iterable} whose element type comes through a second interface. */
  public interface TreeSource extends Iterable<Tree> {}

  public abstract static class Forest<T> {
    public abstract List<T> getTrees();

    public abstract T getFirst();

    public abstract T[] getArray();
  }

  public abstract static class PineForest extends Forest<Tree> {}

  /** Swaps the variable through two levels: {@code Grove<X>} is a {@code Forest<X>}. */
  public abstract static class Grove<X> extends Forest<X> {}

  public abstract static class OakGrove extends Grove<Tree> {}

  public abstract static class BoundedForest<T extends Tree> {
    public abstract List<T> getTrees();
  }

  /** Return types under test, read via reflection. */
  @SuppressWarnings({"unused", "rawtypes"})
  public abstract static class Shapes {
    public abstract List<Tree> list();

    public abstract List<? extends Tree> wildcard();

    public abstract List<?> unknown();

    public abstract List raw();

    public abstract Trees trees();

    public abstract TreeSource source();

    public abstract Tree[] array();

    public abstract int[] primitives();

    public abstract List<List<Tree>> nested();

    public abstract Map<String, Tree> map();

    public abstract Set<Tree> set();

    public abstract Iterator<Tree> iterator();

    public abstract <T> List<T> methodVariable();

    public abstract Tree single();
  }

  private static Type returnType(final String method) throws Exception {
    return Shapes.class.getMethod(method).getGenericReturnType();
  }

  private static Class<?> elementOf(final Type type) {
    return GenericTypes.erase(GenericTypes.elementType(type).orElseThrow());
  }

  @ParameterizedTest
  @CsvSource({
    "list, org.otel.agent.core.path.GenericTypesTest$Tree",
    "wildcard, org.otel.agent.core.path.GenericTypesTest$Tree",
    "unknown, java.lang.Object",
    "raw, java.lang.Object",
    "trees, org.otel.agent.core.path.GenericTypesTest$Tree",
    "source, org.otel.agent.core.path.GenericTypesTest$Tree",
    "array, org.otel.agent.core.path.GenericTypesTest$Tree",
    "primitives, int",
    "nested, java.util.List",
    "set, org.otel.agent.core.path.GenericTypesTest$Tree",
    "methodVariable, java.lang.Object"
  })
  void elementTypeOfIndexableTypes(final String method, final String expected) throws Exception {
    assertEquals(expected, elementOf(returnType(method)).getName());
  }

  @Test
  void nestedElementKeepsItsArguments() throws Exception {
    final Type inner = GenericTypes.elementType(returnType("nested")).orElseThrow();

    assertEquals(Tree.class, elementOf(inner));
  }

  @Test
  void notIndexable() throws Exception {
    assertTrue(GenericTypes.elementType(returnType("map")).isEmpty());
    assertTrue(GenericTypes.elementType(returnType("iterator")).isEmpty());
    assertTrue(GenericTypes.elementType(returnType("single")).isEmpty());
  }

  @Test
  void typeVariableIsResolvedThroughTheSuperclass() throws Exception {
    final Type trees = Forest.class.getMethod("getTrees").getGenericReturnType();
    final Type first = Forest.class.getMethod("getFirst").getGenericReturnType();
    final Type array = Forest.class.getMethod("getArray").getGenericReturnType();

    assertEquals(
        Tree.class, elementOf(GenericTypes.resolve(trees, Forest.class, PineForest.class)));
    assertEquals(Tree.class, GenericTypes.resolve(first, Forest.class, PineForest.class));
    assertEquals(
        Tree.class, elementOf(GenericTypes.resolve(array, Forest.class, PineForest.class)));
  }

  @Test
  void typeVariableIsResolvedThroughTwoLevels() throws Exception {
    final Type trees = Forest.class.getMethod("getTrees").getGenericReturnType();

    assertEquals(Tree.class, elementOf(GenericTypes.resolve(trees, Forest.class, OakGrove.class)));
  }

  @Test
  void typeVariableIsResolvedAgainstAParameterizedContext() throws Exception {
    final Type first = Forest.class.getMethod("getFirst").getGenericReturnType();
    final Type forestOfTrees =
        PropertyAccessorsFixture.class.getMethod("forest").getGenericReturnType();

    assertEquals(Tree.class, GenericTypes.resolve(first, Forest.class, forestOfTrees));
  }

  @Test
  void unresolvedVariableStandsForItsBound() throws Exception {
    final Type trees = BoundedForest.class.getMethod("getTrees").getGenericReturnType();
    final Type unbound = Forest.class.getMethod("getFirst").getGenericReturnType();

    assertEquals(Tree.class, elementOf(trees));
    assertEquals(Object.class, GenericTypes.erase(unbound));
  }

  @Test
  void eraseGivesTheRuntimeClass() throws Exception {
    assertEquals(List.class, GenericTypes.erase(returnType("list")));
    assertEquals(Tree.class, GenericTypes.erase(returnType("single")));
    assertEquals(
        Tree[].class,
        GenericTypes.erase(
            GenericTypes.resolve(
                Forest.class.getMethod("getArray").getGenericReturnType(),
                Forest.class,
                PineForest.class)));
  }

  @Test
  void propertyTypeIsResolvedAgainstItsOwner() throws Exception {
    final Type forestOfTrees =
        PropertyAccessorsFixture.class.getMethod("forest").getGenericReturnType();

    assertEquals(
        Tree.class, PropertyAccessors.propertyGenericType(forestOfTrees, "first").orElseThrow());
    assertEquals(
        Tree.class,
        elementOf(PropertyAccessors.propertyGenericType(PineForest.class, "trees").orElseThrow()));
    assertTrue(PropertyAccessors.propertyGenericType(PineForest.class, "missing").isEmpty());
  }

  /** Provides a parameterized {@code Forest<Tree>} type. */
  public abstract static class PropertyAccessorsFixture {
    public abstract Forest<Tree> forest();
  }
}
