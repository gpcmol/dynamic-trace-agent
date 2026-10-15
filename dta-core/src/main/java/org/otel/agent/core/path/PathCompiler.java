package org.otel.agent.core.path;

import java.util.ArrayList;
import java.util.List;
import org.otel.agent.core.naming.JavaNames;

/**
 * Turns path text into a {@link CompiledPath}.
 *
 * <pre>
 * path     := root ( '[' index ']' )? ( '.' segment )*
 * root     := '$this' | '$return' | '$arg' N        N = 0..127, no leading zeros
 * segment  := identifier ( '[' index ']' )?
 * index    := decimal int 0..999999999, no leading zeros
 * </pre>
 *
 * Attribute paths need at least one segment or a root index ({@code $return[0]}); condition
 * references may be a bare root ({@code $return == null}). The property {@code class} is refused
 * (see {@link ForbiddenTypes}) and a path has at most {@value #MAX_SEGMENTS} segments (a root index
 * is not a segment).
 */
public final class PathCompiler {

  /** Longest path accepted; real paths have a handful of segments. */
  public static final int MAX_SEGMENTS = 32;

  private PathCompiler() {}

  /** A path for {@code <attribute path="…">}: root plus at least one property or a root index. */
  public static CompiledPath attributePath(final String text) throws InvalidPathException {
    final CompiledPath path = compile(text);
    if (path.segments().isEmpty() && !path.hasRootIndex()) {
      throw new InvalidPathException(text, "a property or index is required after " + path.root());
    }
    return path;
  }

  /** A reference used in a condition: a root, optionally followed by properties. */
  public static CompiledPath reference(final String text) throws InvalidPathException {
    return compile(text);
  }

  private static CompiledPath compile(final String text) throws InvalidPathException {
    if (text == null || text.isEmpty()) {
      throw new InvalidPathException(String.valueOf(text), "path is empty");
    }
    final String[] parts = text.split("\\.", -1);
    if (parts.length - 1 > MAX_SEGMENTS) {
      throw new InvalidPathException(text, "more than " + MAX_SEGMENTS + " segments");
    }
    final String rootToken = parts[0];
    final int open = rootToken.indexOf('[');
    final PathRoot root = parseRoot(text, open < 0 ? rootToken : rootToken.substring(0, open));
    final int rootIndex = open < 0 ? PathSegment.NO_INDEX : parseIndex(text, rootToken, open);
    final List<PathSegment> segments = new ArrayList<>(parts.length - 1);
    for (int i = 1; i < parts.length; i++) {
      segments.add(parseSegment(text, parts[i]));
    }
    return new CompiledPath(root, rootIndex, segments);
  }

  private static PathRoot parseRoot(final String text, final String token)
      throws InvalidPathException {
    if (token.equals("$this")) {
      return PathRoot.THIS;
    }
    if (token.equals("$return")) {
      return PathRoot.RETURN;
    }
    if (!token.startsWith("$arg")) {
      throw new InvalidPathException(text, "must start with $this, $return or $argN");
    }
    final String digits = token.substring("$arg".length());
    if (!isCanonicalNumber(digits)) {
      throw new InvalidPathException(
          text, "'" + token + "' needs an argument number such as $arg0");
    }
    if (digits.length() > 3 || Integer.parseInt(digits) > PathRoot.MAX_ARGUMENT_INDEX) {
      throw new InvalidPathException(
          text, "argument index must be 0.." + PathRoot.MAX_ARGUMENT_INDEX);
    }
    return new PathRoot.Argument(Integer.parseInt(digits));
  }

  private static PathSegment parseSegment(final String text, final String token)
      throws InvalidPathException {
    if (token.isEmpty()) {
      throw new InvalidPathException(text, "empty segment");
    }
    final int open = token.indexOf('[');
    if (open < 0) {
      requireIdentifier(text, token);
      return PathSegment.property(token);
    }
    final String property = token.substring(0, open);
    requireIdentifier(text, property);
    return PathSegment.indexed(property, parseIndex(text, token, open));
  }

  /** The {@code [index]} that starts at {@code open} and must end {@code token}. */
  private static int parseIndex(final String text, final String token, final int open)
      throws InvalidPathException {
    if (!token.endsWith("]") || token.indexOf('[', open + 1) >= 0) {
      throw new InvalidPathException(text, "malformed index in '" + token + "'");
    }
    final String digits = token.substring(open + 1, token.length() - 1);
    if (!isCanonicalNumber(digits) || digits.length() > 9) {
      throw new InvalidPathException(
          text, "index in '" + token + "' must be a number 0..999999999");
    }
    return Integer.parseInt(digits);
  }

  private static void requireIdentifier(final String text, final String property)
      throws InvalidPathException {
    if (!JavaNames.isIdentifier(property)) {
      throw new InvalidPathException(text, "'" + property + "' is not a valid property name");
    }
    if (ForbiddenTypes.CLASS_PROPERTY.equals(property)) {
      throw new InvalidPathException(text, "'class' cannot be read");
    }
  }

  /** ASCII digits only, no sign, no leading zeros (except "0" itself). */
  private static boolean isCanonicalNumber(final String digits) {
    if (digits.isEmpty() || (digits.length() > 1 && digits.charAt(0) == '0')) {
      return false;
    }
    for (int i = 0; i < digits.length(); i++) {
      final char c = digits.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }
}
