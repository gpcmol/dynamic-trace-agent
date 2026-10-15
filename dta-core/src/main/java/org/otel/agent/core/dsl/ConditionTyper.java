package org.otel.agent.core.dsl;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.otel.agent.core.dsl.ConditionException.Category;
import org.otel.agent.core.dsl.Expression.ComparisonOperator;
import org.otel.agent.core.dsl.Expression.TextFunctionName;
import org.otel.agent.core.path.CompiledPath;
import org.otel.agent.core.path.PathReader;
import org.otel.agent.core.path.PathRoot;
import org.otel.agent.core.path.PathSegment;
import org.otel.agent.core.path.PropertyAccessors;

/**
 * Type-checks an {@link Expression} against the static types of one instrumented method and builds
 * the executable {@link Condition}. Runs once per (rule, class), never on the hot path.
 *
 * <p>Typing rules (spec {@code condition-dsl}, details in {@code docs/07-dsl.md}):
 *
 * <ul>
 *   <li>Integral types are {@code Long}, floating types {@code Double}.
 *   <li>{@code == !=}: both sides the same normalised type, or one side the {@code null} literal.
 *   <li>{@code lt lte gt gte}: both sides {@code Long}, or both {@code Double}.
 *   <li>{@code in}: value {@code String}/{@code Long}/{@code Double}/{@code Boolean}; candidates of
 *       that type (or {@code null}).
 *   <li>{@code like ilike}: two strings. {@code contains icontains}: String or Collection haystack,
 *       String needle. {@code size}: Collection or array. Arithmetic: two numbers.
 *   <li>A bare operand must be boolean.
 *   <li>Paths: properties must exist on the static type; a {@code Map} on the way is {@code map
 *       unsupported}; an element of a {@code Collection} has no static type ({@code erased
 *       collection type}). A value of static type {@code Object} is not checked further.
 * </ul>
 */
public final class ConditionTyper {

  private final ExitSignature signature;

  private final Class<?> owner;

  private ConditionTyper(final ExitSignature signature, final Class<?> owner) {
    this.signature = signature;
    this.owner = owner;
  }

  /**
   * Builds the executable condition for one instrumented method.
   *
   * @param signature static types of the instrumented method
   * @param owner the application class whose binding will own the condition (see {@link
   *     PathReader#ownedBy})
   */
  public static Condition type(
      final Expression expression, final ExitSignature signature, final Class<?> owner)
      throws ConditionException {
    final ConditionTyper typer = new ConditionTyper(signature, owner);
    if (isLiteralTrue(expression)) {
      return Condition.ALWAYS_TRUE;
    }
    return typer.condition(expression);
  }

  private static boolean isLiteralTrue(final Expression expression) {
    return expression instanceof Expression.Truthy truthy
        && truthy.operand() instanceof Operand.Literal literal
        && Boolean.TRUE.equals(literal.value());
  }

  private Condition condition(final Expression expression) throws ConditionException {
    return switch (expression) {
      case Expression.And and -> new LogicalConditions.All(conditions(and.terms()));
      case Expression.Or or -> new LogicalConditions.Any(conditions(or.terms()));
      case Expression.Not not -> new LogicalConditions.Negation(condition(not.term()));
      case Expression.Comparison comparison -> comparison(comparison);
      case Expression.Membership membership -> membership(membership);
      case Expression.TextFunction function -> textFunction(function);
      case Expression.Truthy truthy -> truthy(truthy);
    };
  }

  private Condition[] conditions(final List<Expression> terms) throws ConditionException {
    final Condition[] result = new Condition[terms.size()];
    for (int i = 0; i < result.length; i++) {
      result[i] = condition(terms.get(i));
    }
    return result;
  }

  private Condition truthy(final Expression.Truthy truthy) throws ConditionException {
    final ValueNode operand = value(truthy.operand());
    if (operand.type() != Boolean.class) {
      throw mismatch("a condition must be boolean, got " + Types.describe(operand.type()));
    }
    return new ComparisonConditions.Truthy(operand);
  }

  private Condition comparison(final Expression.Comparison comparison) throws ConditionException {
    final ComparisonOperator operator = comparison.operator();
    final ValueNode left = value(comparison.left());
    final ValueNode right = value(comparison.right());
    final boolean leftNull = left.type() == Types.NULL;
    final boolean rightNull = right.type() == Types.NULL;

    if (leftNull || rightNull) {
      if (!operator.isEquality()) {
        throw mismatch("null can only be compared with == or !=, not " + operator);
      }
      final ValueNode other = leftNull ? right : left;
      return new ComparisonConditions.NullCheck(other, operator == ComparisonOperator.EQUAL);
    }
    if (left.type() != right.type()) {
      throw mismatch(
          Types.describe(left.type()) + " " + operator + " " + Types.describe(right.type()));
    }
    if (Types.isNumeric(left.type())) {
      return new ComparisonConditions.Numeric(operator, left, right, left.type() == Double.class);
    }
    if (!operator.isEquality()) {
      throw mismatch(operator + " needs numbers, got " + Types.describe(left.type()));
    }
    return new ComparisonConditions.Equality(operator == ComparisonOperator.EQUAL, left, right);
  }

  private Condition membership(final Expression.Membership membership) throws ConditionException {
    final ValueNode value = value(membership.value());
    final Class<?> type = value.type();
    final List<Object> candidates = new ArrayList<>();
    for (final Operand.Literal literal : membership.candidates()) {
      if (literal.value() == null) {
        continue;
      }
      final Class<?> literalType = Types.normalise(literal.value().getClass());
      if (literalType != type) {
        throw mismatch(
            "'in' list mixes " + Types.describe(type) + " with " + Types.describe(literalType));
      }
      candidates.add(literal.value());
    }
    if (type == Long.class) {
      return new MembershipConditions.InLongs(
          value, candidates.stream().mapToLong(c -> (Long) c).toArray());
    }
    if (type == Double.class) {
      return new MembershipConditions.InDoubles(
          value, candidates.stream().mapToDouble(c -> (Double) c).toArray());
    }
    if (type == String.class) {
      final Set<String> strings = new HashSet<>();
      candidates.forEach(c -> strings.add((String) c));
      return new MembershipConditions.InStrings(value, strings);
    }
    if (type == Boolean.class) {
      return new MembershipConditions.InBooleans(
          value, candidates.contains(Boolean.TRUE), candidates.contains(Boolean.FALSE));
    }
    throw mismatch("'in' needs a string, number or boolean, got " + Types.describe(type));
  }

  private Condition textFunction(final Expression.TextFunction function) throws ConditionException {
    final TextFunctionName name = function.function();
    final ValueNode haystack = value(function.haystack());
    final ValueNode needle = value(function.needle());
    if (needle.type() != String.class) {
      throw mismatch(
          name + " needs a string as second argument, got " + Types.describe(needle.type()));
    }
    return switch (name) {
      case LIKE, ILIKE -> like(name == TextFunctionName.ILIKE, haystack, needle, function);
      case CONTAINS, ICONTAINS -> contains(name, haystack, needle);
    };
  }

  private Condition like(
      final boolean ignoreCase,
      final ValueNode value,
      final ValueNode pattern,
      final Expression.TextFunction function)
      throws ConditionException {
    if (value.type() != String.class) {
      throw mismatch(function.function() + " needs a string, got " + Types.describe(value.type()));
    }
    if (function.needle() instanceof Operand.Literal literal) {
      return new TextConditions.LikeLiteral(
          value, LikePattern.compile((String) literal.value(), ignoreCase));
    }
    return new TextConditions.LikeDynamic(value, pattern, ignoreCase);
  }

  private Condition contains(
      final TextFunctionName name, final ValueNode haystack, final ValueNode needle)
      throws ConditionException {
    final Class<?> type = haystack.type();
    if (type != String.class && type != Object.class && !Collection.class.isAssignableFrom(type)) {
      throw mismatch(name + " needs a string or collection, got " + Types.describe(type));
    }
    return new TextConditions.Contains(haystack, needle, name == TextFunctionName.ICONTAINS);
  }

  private ValueNode value(final Operand operand) throws ConditionException {
    return switch (operand) {
      case Operand.Literal literal -> literal(literal);
      case Operand.Reference reference -> reference(reference.path());
      case Operand.Size size -> size(size);
      case Operand.Arithmetic arithmetic -> arithmetic(arithmetic);
    };
  }

  private static ValueNode literal(final Operand.Literal literal) {
    final Object constant = literal.value();
    return new ValueNode.LiteralNode(constant, constant == null ? Types.NULL : constant.getClass());
  }

  private ValueNode size(final Operand.Size size) throws ConditionException {
    final ValueNode operand = value(size.operand());
    final Class<?> type = operand.type();
    if (Map.class.isAssignableFrom(type)) {
      throw new ConditionException(Category.MAP_UNSUPPORTED, "size() of a Map is not supported");
    }
    if (type != Object.class && !type.isArray() && !Collection.class.isAssignableFrom(type)) {
      throw mismatch("size() needs a collection or array, got " + Types.describe(type));
    }
    return new ValueNode.SizeNode(operand);
  }

  private ValueNode arithmetic(final Operand.Arithmetic arithmetic) throws ConditionException {
    final ValueNode left = value(arithmetic.left());
    final ValueNode right = value(arithmetic.right());
    if (!Types.isNumeric(left.type()) || !Types.isNumeric(right.type())) {
      throw mismatch(
          "arithmetic needs numbers, got "
              + Types.describe(left.type())
              + " and "
              + Types.describe(right.type()));
    }
    final boolean floating = left.type() == Double.class || right.type() == Double.class;
    return new ValueNode.ArithmeticNode(arithmetic.operator(), left, right, floating);
  }

  private ValueNode reference(final CompiledPath path) throws ConditionException {
    return new ValueNode.PathNode(
        PathReader.ownedBy(path, owner), Types.normalise(staticType(path)));
  }

  /** The static type of a path, checking every property on the way. */
  private Class<?> staticType(final CompiledPath path) throws ConditionException {
    Class<?> type = rootType(path.root());
    if (path.hasRootIndex() && type != Object.class) {
      type = elementType(path, path.root() + "[" + path.rootIndex() + "]", type);
    }
    for (final PathSegment segment : path.segments()) {
      if (type == Object.class) {
        return Object.class;
      }
      if (Map.class.isAssignableFrom(type)) {
        throw new ConditionException(
            Category.MAP_UNSUPPORTED, "'" + path + "' navigates into a Map");
      }
      if (type.isArray() || Iterable.class.isAssignableFrom(type)) {
        throw collectionNotSupported(path, segment);
      }
      final Class<?> owningType = type;
      type =
          PropertyAccessors.propertyType(owningType, segment.property())
              .orElseThrow(
                  () ->
                      new ConditionException(
                          Category.UNKNOWN_PROPERTY,
                          PropertyAccessors.describeMissing(owningType, segment.property())
                              + " (in '"
                              + path
                              + "')"));
      if (segment.isIndexed()) {
        type = elementType(path, segment.toString(), type);
      }
    }
    return type;
  }

  /**
   * The type of {@code indexed[i]}: the component type of an array.
   *
   * @param segment how the indexed part is shown in messages ({@code seats[0]}, {@code $return[0]})
   */
  private static Class<?> elementType(
      final CompiledPath path, final String segment, final Class<?> type)
      throws ConditionException {
    if (type.isArray()) {
      return type.getComponentType();
    }
    if (Iterable.class.isAssignableFrom(type)) {
      throw new ConditionException(
          Category.ERASED_COLLECTION_TYPE,
          "the element type of '"
              + segment
              + "' in '"
              + path
              + "' is unknown (generic collection)");
    }
    throw mismatch("'" + segment + "' in '" + path + "' cannot be indexed");
  }

  /**
   * A property applied to a collection would flatten it into a list; conditions do not support that
   * (use {@code [i]} for one element, or {@code size()}/{@code contains()} on the collection).
   */
  private static ConditionException collectionNotSupported(
      final CompiledPath path, final PathSegment segment) {
    return new ConditionException(
        Category.ERASED_COLLECTION_TYPE,
        "'" + segment.property() + "' is applied to a collection in '" + path + "'");
  }

  private Class<?> rootType(final PathRoot root) {
    return switch (root) {
      case PathRoot.This ignored -> signature.self();
      case PathRoot.Return ignored ->
          signature.returnType() == void.class ? Object.class : signature.returnType();
      case PathRoot.Argument argument ->
          argument.index() < signature.parameterTypes().size()
              ? signature.parameterTypes().get(argument.index())
              : Object.class;
    };
  }

  private static ConditionException mismatch(final String detail) {
    return new ConditionException(Category.TYPE_MISMATCH, detail);
  }
}
