package org.otel.agent.core.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PathCompilerTest {

  @Test
  void compilesRootsSegmentsAndIndexes() throws Exception {
    final CompiledPath path = PathCompiler.attributePath("$arg0.passengers[1].name");

    assertEquals(new PathRoot.Argument(0), path.root());
    assertEquals(
        List.of(PathSegment.indexed("passengers", 1), PathSegment.property("name")),
        path.segments());
    assertEquals("$arg0.passengers[1].name", path.toString());
  }

  @Test
  void compilesThisReturnAndHighestArgument() throws Exception {
    assertEquals(PathRoot.THIS, PathCompiler.attributePath("$this.brand").root());
    assertEquals(PathRoot.RETURN, PathCompiler.attributePath("$return.accepted").root());
    assertEquals(new PathRoot.Argument(127), PathCompiler.attributePath("$arg127.x").root());
  }

  @Test
  void bareRootIsAReferenceButNotAnAttributePath() throws Exception {
    assertTrue(PathCompiler.reference("$return").segments().isEmpty());

    final InvalidPathException exception =
        assertThrows(InvalidPathException.class, () -> PathCompiler.attributePath("$arg0"));
    assertTrue(exception.getMessage().contains("property or index is required"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"$return[0].color", "$arg1[2].name", "$this[0].id", "$return[0]"})
  void compilesAnIndexedRoot(final String text) throws Exception {
    final CompiledPath path = PathCompiler.attributePath(text);

    assertTrue(path.hasRootIndex());
    assertEquals(text, path.toString());
  }

  @Test
  void rootIndexIsNotASegment() throws Exception {
    final CompiledPath path = PathCompiler.attributePath("$return[3].trees[1].color");

    assertEquals(PathRoot.RETURN, path.root());
    assertEquals(3, path.rootIndex());
    assertEquals(
        List.of(PathSegment.indexed("trees", 1), PathSegment.property("color")), path.segments());
    assertEquals(
        PathCompiler.MAX_SEGMENTS,
        PathCompiler.attributePath("$this[0]" + ".a".repeat(PathCompiler.MAX_SEGMENTS))
            .segments()
            .size());
  }

  @Test
  void pathWithoutRootIndexHasNone() throws Exception {
    assertEquals(PathSegment.NO_INDEX, PathCompiler.attributePath("$return.color").rootIndex());
  }

  @Test
  void rootAndSegmentIndexShareTheirErrorMessages() {
    final InvalidPathException root =
        assertThrows(InvalidPathException.class, () -> PathCompiler.attributePath("$return[01]"));
    final InvalidPathException segment =
        assertThrows(
            InvalidPathException.class, () -> PathCompiler.attributePath("$return.trees[01]"));

    assertTrue(root.getMessage().contains("must be a number 0..999999999"));
    assertTrue(segment.getMessage().contains("must be a number 0..999999999"));
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "brand",
        "com.example.Car.brand",
        "$self.brand",
        "$arg.brand",
        "$arg128.brand",
        "$arg01.brand",
        "$arg-1.brand",
        "$arg9999999999.brand",
        "$this..brand",
        "$this.brand.",
        "$this.[0]",
        "$this.items[]",
        "$this.items[-1]",
        "$this.items[01]",
        "$this.items[1][2]",
        "$this.items[1",
        "$this.items[a]",
        "$this.items[9999999999]",
        "$this.1brand",
        "$this.bra nd",
        "$this.class",
        "$this.class.classLoader",
        "$arg0.owner.class.name",
        "$return[]",
        "$return[01]",
        "$return[-1]",
        "$return[0][1]",
        "$return[0]x",
        "$return[0].",
        "$return [0]",
        "$return[a]",
        "$return[0",
        "$arg0[1000000000]",
        "$arg[0]",
        "[0].color",
        "$return[0].class"
      })
  void rejectsInvalidPaths(final String text) {
    assertThrows(InvalidPathException.class, () -> PathCompiler.attributePath(text));
  }

  @Test
  void errorMessageNamesThePath() {
    final InvalidPathException exception =
        assertThrows(InvalidPathException.class, () -> PathCompiler.attributePath("$arg0..x"));

    assertTrue(exception.getMessage().contains("'$arg0..x'"));
  }

  @Test
  void pathLengthIsLimited() throws Exception {
    final String longest = "$this" + ".a".repeat(PathCompiler.MAX_SEGMENTS);

    assertEquals(PathCompiler.MAX_SEGMENTS, PathCompiler.attributePath(longest).segments().size());
    assertThrows(InvalidPathException.class, () -> PathCompiler.attributePath(longest + ".a"));
  }
}
