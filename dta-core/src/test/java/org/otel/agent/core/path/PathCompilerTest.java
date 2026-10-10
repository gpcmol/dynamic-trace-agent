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
    assertTrue(exception.getMessage().contains("property is required"));
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
        "$this.bra nd"
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
}
