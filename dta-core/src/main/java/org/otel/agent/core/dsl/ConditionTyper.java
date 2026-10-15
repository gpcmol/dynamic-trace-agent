package org.otel.agent.core.dsl;

import java.lang.reflect.Type;
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
import org.otel.agent.core.path.GenericTypes;
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
 *       unsupported}; a property applied to a whole collection is {@code erased collection type}.
 *       Types are generic: {@code [i]} on a {@code List<Tree>} is a {@code Tree}, type variables
 *       are resolved through the receiver's class hierarchy (see {@link GenericTypes}).
 *   <li>A value of static type {@code Object} (an element of {@code List<?>}, an {@code Object}
 *       getter) is dynamic: it is not checked further and takes the type of its counterpart,
 *       checked when evaluated ({@link ValueNode.Coerced}).
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
    final ValueNode operand = coerce(value(truthy.operand()), Boolean.class);
    if (operand.type() != Boolean.class) {
      throw mismatch("a condition must be boolean, got " + Types.describe(operand.type()));
    }
    return new ComparisonConditions.Truthy(operand);
  }

  private Condition comparison(final Expression.Comparison comparison) throws ConditionException {
    final ComparisonOperator operator = comparison.operator();
    final ValueNode typedLeft = value(comparison.left());
    final ValueNode right = coerce(value(comparison.right()), typedLeft.type());
    final ValueNode left = coerce(typedLeft, right.type());
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
    final ValueNode value = coerce(value(membership.value()), candidateType(membership));
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

  /** The normalised type of the first non-null {@code in} candidate, or {@code Object}. */
  private static Class<?> candidateType(final Expression.Membership membership) {
    for (final Operand.Literal literal : membership.candidates()) {
      if (literal.value() != null) {
        return Types.normalise(literal.value().getClass());
      }
    }
    return Object.class;
  }

  private Condition textFunction(final Expression.TextFunction function) throws ConditionException {
    final TextFunctionName name = function.function();
    final ValueNode haystack = value(function.haystack());
    final ValueNode needle = coerce(value(function.needle()), String.class);
    if (needle.type() != String.class) {
      throw mismatch(
          name + " needs a string as second argument, got " + Types.describe(needle.type()));
    }
    return switch (name) {
      case LIKE, ILIKE ->
          like(name == TextFunctionName.ILIKE, coerce(haystack, String.class), needle, function);
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
    final ValueNode typedLeft = value(arithmetic.left());
    final ValueNode right = coerce(value(arithmetic.right()), typedLeft.type());
    final ValueNode left = coerce(typedLeft, right.type());
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
    Type type = rootType(path.root());
    if (path.hasRootIndex()) {
      type = elementType(path, path.root() + "[" + path.rootIndex() + "]", type);
    }
    for (final PathSegment segment : path.segments()) {
      final Class<?> raw = GenericTypes.erase(type);
      if (raw == Object.class) {
        return Object.class;
      }
      if (Map.class.isAssignableFrom(raw)) {
        throw new ConditionException(
            Category.MAP_UNSUPPORTED, "'" + path + "' navigates into a Map");
      }
      if (raw.isArray() || Iterable.class.isAssignableFrom(raw)) {
        throw collectionNotSupported(path, segment);
      }
      type =
          PropertyAccessors.propertyGenericType(type, segment.property())
              .orElseThrow(
                  () ->
                      new ConditionException(
                          Category.UNKNOWN_PROPERTY,
                          PropertyAccessors.describeMissing(raw, segment.property())
                              + " (in '"
                              + path
                              + "')"));
      if (segment.isIndexed()) {
        type = elementType(path, segment.toString(), type);
      }
    }
    return GenericTypes.erase(type);
  }

  /**
   * The type of {@code indexed[i]}: the generic element type of an array or {@code Iterable};
   * {@code Object} (dynamic) when it is not in the code or the indexed value is itself {@code
   * Object}.
   *
   * @param segment how the indexed part is shown in messages ({@code seats[0]}, {@code $return[0]})
   */
  private static Type elementType(final CompiledPath path, final String segment, final Type type)
      throws ConditionException {
    if (GenericTypes.erase(type) == Object.class) {
      return Object.class;
    }
    if (Map.class.isAssignableFrom(GenericTypes.erase(type))) {
      throw mismatch("'" + segment + "' in '" + path + "' cannot be indexed");
    }
    return GenericTypes.elementType(type)
        .orElseThrow(() -> mismatch("'" + segment + "' in '" + path + "' cannot be indexed"));
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

  private Type rootType(final PathRoot root) {
    return switch (root) {
      case PathRoot.This ignored -> signature.self();
      case PathRoot.Return ignored ->
          signature.returnType() == void.class ? Object.class : signature.genericReturnType();
      case PathRoot.Argument argument ->
          argument.index() < signature.genericParameterTypes().size()
              ? signature.genericParameterTypes().get(argument.index())
              : Object.class;
    };
  }

  /**
   * A dynamic operand (static type {@code Object}) facing a {@code String}, {@code Long}, {@code
   * Double} or {@code Boolean} takes that type, checked when evaluated; any other node is returned
   * unchanged.
   */
  private static ValueNode coerce(final ValueNode node, final Class<?> type) {
    return node.type() == Object.class && ValueNode.Coerced.supports(type)
        ? new ValueNode.Coerced(node, type)
        : node;
  }

  private static ConditionException mismatch(final String detail) {
    return new ConditionException(Category.TYPE_MISMATCH, detail);
  }
}
