package org.otel.agent.core.dsl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.otel.agent.core.dsl.ConditionException.Category;
import org.otel.agent.core.path.ExitFrame;

/** Typing and evaluation of conditions against the method {@code Garage.park(Car, int)}. */
class ConditionTest {

  public record Passenger(String name) {
    public String getName() {
      return name;
    }
  }

  public static final class Car {
    private final String brand;
    private final String model;
    private final int year;
    private final long mileage;
    private final double price;
    private final boolean electric;
    private final List<Passenger> passengers;
    private final List<String> tags;
    private final Passenger[] seats;

    public Car(
        final String brand,
        final String model,
        final int year,
        final long mileage,
        final double price,
        final boolean electric,
        final List<Passenger> passengers,
        final List<String> tags,
        final Passenger[] seats) {
      this.brand = brand;
      this.model = model;
      this.year = year;
      this.mileage = mileage;
      this.price = price;
      this.electric = electric;
      this.passengers = passengers;
      this.tags = tags;
      this.seats = seats;
    }

    public String getBrand() {
      return brand;
    }

    public String getModel() {
      return model;
    }

    public int getYear() {
      return year;
    }

    public long getMileage() {
      return mileage;
    }

    public double getPrice() {
      return price;
    }

    public boolean isElectric() {
      return electric;
    }

    public List<Passenger> getPassengers() {
      return passengers;
    }

    public List<String> getTags() {
      return tags;
    }

    public Passenger[] getSeats() {
      return seats;
    }

    public Map<String, String> getLabels() {
      return Map.of();
    }

    public Object getAnything() {
      return "dynamic";
    }
  }

  public record Result(boolean accepted, String status) {
    public boolean isAccepted() {
      return accepted;
    }

    public String getStatus() {
      return status;
    }
  }

  /** Stand-in for the selected class. */
  public static final class Garage {}

  private static final ExitSignature PARK =
      new ExitSignature(Garage.class, List.of(Car.class, int.class), Result.class);

  private static final Car BMW =
      new Car(
          "bmw",
          "X5",
          2024,
          50_000L,
          65_000.5d,
          false,
          List.of(new Passenger("Jan"), new Passenger("Piet")),
          List.of("vip", "lease"),
          new Passenger[] {new Passenger("Kees")});

  private record Frame(Object self, Object returned, List<Object> arguments) implements ExitFrame {
    Frame(final Object self, final Object returned, final Object... arguments) {
      this(self, returned, java.util.Arrays.asList(arguments));
    }

    @Override
    public Object argument(final int index) {
      return index < arguments.size() ? arguments.get(index) : null;
    }
  }

  private static final Frame PARK_BMW =
      new Frame(new Garage(), new Result(true, "accepted"), BMW, 7);

  private static Condition compile(final String text) throws ConditionException {
    return ConditionTyper.type(ExpressionParser.parse(text), PARK, ConditionTest.class);
  }

  private static boolean eval(final String text, final Frame frame) throws ConditionException {
    return Condition.evaluate(compile(text), frame);
  }

  private static boolean eval(final String text) throws ConditionException {
    return eval(text, PARK_BMW);
  }

  @Nested
  class Evaluation {

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
          "$arg0.brand == 'bmw' | true",
          "$arg0.brand != 'bmw' | false",
          "$arg0.brand == 'BMW' | false",
          "$arg0.mileage gt 0 | true",
          "$arg0.mileage lte 50000 | true",
          "$arg0.mileage lt 50000 | false",
          "$arg0.year gte 2024 | true",
          "$arg0.price gt 65000.0 | true",
          "$arg1 == 7 | true",
          "$arg0.electric | false",
          "not $arg0.electric | true",
          "$return.accepted | true",
          "$return.status == 'accepted' | true",
          "$arg0.brand == 'bmw' and ilike($arg0.model, 'x%') | true",
          "$arg0.brand == 'audi' or $arg0.mileage gt 10 | true",
          "($arg0.brand == 'audi' or $arg0.brand == 'bmw') and not $arg0.electric | true",
          "$arg0.brand in ['audi', 'bmw'] | true",
          "$arg0.brand in ['audi', 'vw'] | false",
          "$arg0.year in [2023, 2024] | true",
          "$arg0.mileage in [0, 50000] | true",
          "$arg0.price in [1.5, 65000.5] | true",
          "$arg0.electric in [false] | true",
          "size($arg0.passengers) == 2 | true",
          "size($arg0.seats) == 1 | true",
          "contains($arg0.tags, 'vip') | true",
          "contains($arg0.tags, 'VIP') | false",
          "icontains($arg0.tags, 'VIP') | true",
          "contains($arg0.brand, 'm') | true",
          "icontains($arg0.brand, 'BMW') | true",
          "icontains($arg0.brand, 'BM') | false",
          "like($arg0.model, 'X_') | true",
          "like($arg0.model, 'x%') | false",
          "ilike($arg0.model, '%5') | true",
          "$arg0.seats[0].name == 'Kees' | true",
          "$arg0.passengers[1].name == 'Piet' | true",
          "contains($arg0.tags, 'vip') and $arg0.tags[0] == 'vip' | true",
          "$arg0.mileage + 1 == 50001 | true",
          "$arg0.mileage - 2 * 25000 == 0 | true",
          "($arg0.mileage + 50000) / 2 == 50000 | true",
          "$arg0.mileage % 7 == 6 | true",
          "$arg0.mileage / 0 == 0 | false",
          "$arg0.price / 2 gt 32500.0 | true",
          "true | true",
          "$arg0 != null | true",
          "$arg3 == null | true",
          "$arg0.anything == null | false"
        })
    void evaluates(final String text, final boolean expected) throws Exception {
      assertEquals(expected, eval(text), text);
    }

    @Test
    void nullOperandIsFalseExceptForNullLiteral() throws Exception {
      final Frame nullCar = new Frame(new Garage(), null, null, 1);

      assertFalse(eval("$arg0.brand == 'bmw'", nullCar));
      assertFalse(eval("$arg0.brand != 'bmw'", nullCar));
      assertFalse(eval("$arg0.mileage gt 0", nullCar));
      assertFalse(eval("$arg0.brand in ['bmw']", nullCar));
      assertFalse(eval("like($arg0.model, '%')", nullCar));
      assertTrue(eval("$arg0.brand == null", nullCar));
      assertTrue(eval("$arg0 == null", nullCar));
      assertTrue(eval("size($arg0.passengers) == 0", nullCar));
      assertFalse(eval("$return.accepted", nullCar));
    }

    @Test
    void andShortCircuits() throws Exception {
      // The second term would throw (ClassCastException) if evaluated: $arg1 is an Integer.
      final Frame frame = new Frame(new Garage(), null, BMW, "not an int");

      assertFalse(eval("$arg0.brand == 'audi' and $arg1 gt 1", frame));
    }

    @Test
    void runtimeFailureCountsAsFalse() throws Exception {
      final Frame wrongType = new Frame(new Garage(), null, BMW, "not an int");

      assertFalse(eval("$arg1 gt 1", wrongType));
    }

    @Test
    void literalTrueIsTheConstantCondition() throws Exception {
      assertSame(Condition.ALWAYS_TRUE, compile("true"));
    }
  }

  @Nested
  class Typing {

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
          "$arg0.brand == 42 | TYPE_MISMATCH",
          "$arg0.mileage == 'a' | TYPE_MISMATCH",
          "$arg0.mileage gt 1.5 | TYPE_MISMATCH",
          "$arg0.brand gt 'a' | TYPE_MISMATCH",
          "$arg0.brand lt null | TYPE_MISMATCH",
          "$arg0.brand | TYPE_MISMATCH",
          "$arg0.year in ['a'] | TYPE_MISMATCH",
          "$arg0 in [1] | TYPE_MISMATCH",
          "like($arg0.year, '1%') | TYPE_MISMATCH",
          "like($arg0.brand, 1) | TYPE_MISMATCH",
          "contains($arg0.year, 'a') | TYPE_MISMATCH",
          "size($arg0.brand) gt 1 | TYPE_MISMATCH",
          "$arg0.brand + 1 == 1 | TYPE_MISMATCH",
          "$arg0.brand[0] == 'b' | TYPE_MISMATCH",
          "$arg0.colour == 'red' | UNKNOWN_PROPERTY",
          "$return.missing == 'x' | UNKNOWN_PROPERTY",
          "$arg0.passengers.name == 'Jan' | ERASED_COLLECTION_TYPE",
          "$arg0.labels.a == 'b' | MAP_UNSUPPORTED",
          "size($arg0.labels) == 0 | MAP_UNSUPPORTED"
        })
    void rejects(final String text, final Category category) {
      final ConditionException exception =
          assertThrows(ConditionException.class, () -> compile(text));

      assertEquals(category, exception.category(), exception.getMessage());
    }

    @Test
    void unknownPropertyMessageNamesPropertyAndClass() {
      final ConditionException exception =
          assertThrows(ConditionException.class, () -> compile("$arg0.colour == 'red'"));

      assertTrue(exception.getMessage().contains("'colour'"));
      assertTrue(exception.getMessage().contains(Car.class.getName()));
    }

    @Test
    void valuesOfStaticTypeObjectAreNotCheckedFurther() throws Exception {
      // getAnything() returns Object: '.length' cannot be checked statically, so it is accepted.
      compile("$arg0.anything.length == null");
    }

    @Test
    void argumentBeyondTheSignatureIsObject() throws Exception {
      compile("$arg5 == null");
    }
  }

  @Nested
  class RootIndex {

    /** {@code Passenger[] board(Passenger[] queue, List<Passenger> list)}. */
    private static final ExitSignature BOARD =
        new ExitSignature(Garage.class, List.of(Passenger[].class, List.class), Passenger[].class);

    private static final Frame BOARDED =
        new Frame(
            new Garage(),
            new Passenger[] {new Passenger("Kees")},
            new Passenger[] {new Passenger("Jan"), new Passenger("Piet")},
            List.of(new Passenger("Eva")));

    private static Condition compileBoard(final String text) throws ConditionException {
      return ConditionTyper.type(ExpressionParser.parse(text), BOARD, ConditionTest.class);
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
          "$return[0].name == 'Kees' | true",
          "$return[0].name == 'Jan' | false",
          "$arg0[1].name == 'Piet' | true",
          "$return[0] != null | true",
          "$return[1] == null | true",
          "$arg0[5].name == null | true"
        })
    void arrayRootIsTyped(final String text, final boolean expected) throws Exception {
      assertEquals(expected, Condition.evaluate(compileBoard(text), BOARDED), text);
    }

    @Test
    void nullRootIsFalse() throws Exception {
      final Frame nothing = new Frame(new Garage(), null, null, null);

      assertFalse(Condition.evaluate(compileBoard("$return[0].name == 'Kees'"), nothing));
      assertTrue(Condition.evaluate(compileBoard("$return[0] == null"), nothing));
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
          "$return[0].missing == 'x' | UNKNOWN_PROPERTY",
          "$return[0].name == 42 | TYPE_MISMATCH"
        })
    void rejectsOnArraySignature(final String text, final Category category) {
      final ConditionException exception =
          assertThrows(ConditionException.class, () -> compileBoard(text));

      assertEquals(category, exception.category(), exception.getMessage());
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {"$arg0[0].brand == 'bmw' | TYPE_MISMATCH", "$arg1[0] == 1 | TYPE_MISMATCH"})
    void rejectsIndexOnANonCollection(final String text, final Category category) {
      final ConditionException exception =
          assertThrows(ConditionException.class, () -> compile(text));

      assertEquals(category, exception.category(), exception.getMessage());
      assertTrue(exception.getMessage().contains("cannot be indexed"), exception.getMessage());
    }

    @Test
    void indexOnANonCollectionNamesTheIndexedRoot() {
      final ConditionException exception =
          assertThrows(ConditionException.class, () -> compile("$arg0[0].brand == 'bmw'"));

      assertTrue(exception.getMessage().contains("'$arg0[0]'"), exception.getMessage());
    }

    @Test
    void rawListRootIsDynamic() throws Exception {
      assertTrue(Condition.evaluate(compileBoard("$arg1[0].name == 'Eva'"), BOARDED));
    }

    @Test
    void rootOfStaticTypeObjectIsNotCheckedFurther() throws Exception {
      compile("$arg5[0].anything == null");
    }
  }

  @Nested
  class Like {

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
          "abc | abc | true",
          "abc | ab | false",
          "abc | a% | true",
          "abc | %c | true",
          "abc | %b% | true",
          "abc | a_c | true",
          "abc | a__ | true",
          "abc | a___ | false",
          "abc | % | true",
          "'' | % | true",
          "'' | _ | false",
          "abcabc | %bc%bc | true",
          "abcab | a%b%c | false",
          "aXbXc | a%X%c | true",
          "mississippi | m%ss%pi | true"
        })
    void likeIsCaseSensitive(final String value, final String pattern, final boolean expected) {
      assertEquals(expected, LikePattern.compile(pattern, false).matches(value));
    }

    @Test
    void ilikeFoldsAsciiOnly() {
      assertTrue(LikePattern.compile("x%", true).matches("X5"));
      assertFalse(LikePattern.compile("ß", true).matches("SS"));
    }
  }

  @Nested
  class GenericAndDynamic {

    /** Return types the typer has to read from generic signatures. */
    @SuppressWarnings({"unused", "rawtypes"})
    public static final class Shop {
      public List<Passenger> listed() {
        return List.of();
      }

      public List<?> unknown() {
        return List.of();
      }

      public List raw() {
        return List.of();
      }

      public <T> List<T> generic() {
        return List.of();
      }
    }

    private static Condition compileFor(final String method, final String text) throws Exception {
      final java.lang.reflect.Method returning = Shop.class.getMethod(method);
      return ConditionTyper.type(
          ExpressionParser.parse(text),
          new ExitSignature(
              Shop.class, List.of(), List.class, List.of(), returning.getGenericReturnType()),
          ConditionTest.class);
    }

    private static boolean evalFor(final String method, final String text, final Object returned)
        throws Exception {
      return Condition.evaluate(compileFor(method, text), new Frame(new Shop(), returned));
    }

    @Test
    void genericElementIsTyped() throws Exception {
      assertTrue(evalFor("listed", "$return[0].name == 'Jan'", List.of(new Passenger("Jan"))));
    }

    @Test
    void typoAfterAGenericElementIsReported() {
      final ConditionException exception =
          assertThrows(
              ConditionException.class, () -> compileFor("listed", "$return[0].nmae == 'x'"));

      assertEquals(Category.UNKNOWN_PROPERTY, exception.category());
    }

    @Test
    void typeErrorAfterAGenericElementIsReported() {
      final ConditionException exception =
          assertThrows(
              ConditionException.class, () -> compileFor("listed", "$return[0].name gt 1"));

      assertEquals(Category.TYPE_MISMATCH, exception.category());
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {"unknown", "raw", "generic"})
    void unknownElementTypeIsDynamic(final String method) throws Exception {
      assertTrue(evalFor(method, "$return[0].name == 'Jan'", List.of(new Passenger("Jan"))));
      assertFalse(evalFor(method, "$return[0].name == 'Jan'", List.of("no name property")));
      assertFalse(evalFor(method, "$return[0].name != 'Jan'", List.of("no name property")));
    }

    @Test
    void dynamicValueMustHaveTheTypeOfItsCounterpart() throws Exception {
      assertTrue(evalFor("unknown", "$return[0] == 'text'", List.of("text")));
      assertTrue(evalFor("unknown", "$return[0] gt 10", List.of(11)));
      assertTrue(evalFor("unknown", "$return[0] gt 10", List.of((byte) 11)));
      assertFalse(evalFor("unknown", "$return[0] gt 10", List.of(11.5d)));
      assertFalse(evalFor("unknown", "$return[0] gt 10", List.of("11")));
      assertTrue(evalFor("unknown", "$return[0] gt 10.5", List.of(11.5f)));
      assertFalse(evalFor("unknown", "$return[0] gt 10.5", List.of(11)));
      assertTrue(evalFor("unknown", "10 lt $return[0]", List.of(11L)));
    }

    @Test
    void dynamicValueAsConditionMustBeBoolean() throws Exception {
      assertTrue(evalFor("raw", "$return[0]", List.of(true)));
      assertFalse(evalFor("raw", "$return[0]", List.of("true")));
      assertTrue(evalFor("raw", "not $return[0]", List.of(false)));
    }

    @Test
    void dynamicValueInFunctionsAndMembership() throws Exception {
      assertTrue(evalFor("unknown", "$return[0] in ['bmw', 'audi']", List.of("bmw")));
      assertFalse(evalFor("unknown", "$return[0] in ['bmw', 'audi']", List.of(1L)));
      assertTrue(evalFor("unknown", "$return[0] in [1, 2]", List.of(2)));
      assertTrue(evalFor("unknown", "like($return[0], 'b%')", List.of("bmw")));
      assertTrue(evalFor("unknown", "ilike($return[0], 'B%')", List.of("bmw")));
      assertTrue(evalFor("unknown", "contains($return[0], 'm')", List.of("bmw")));
      assertTrue(evalFor("unknown", "like('bmw', $return[0])", List.of("b%")));
    }

    @Test
    void dynamicValueInArithmetic() throws Exception {
      assertTrue(evalFor("unknown", "$return[0] + 1 == 6", List.of(5)));
      assertFalse(evalFor("unknown", "$return[0] + 1 == 6", List.of(5.0d)));
    }

    @Test
    void twoDynamicValuesCompareByEquality() throws Exception {
      assertTrue(evalFor("unknown", "$return[0] == $return[1]", List.of("a", "a")));
      assertFalse(evalFor("unknown", "$return[0] == $return[1]", List.of("a", 1)));
    }

    @ParameterizedTest
    @CsvSource(
        delimiter = '|',
        value = {
          "$return[0] + $return[1] == 2 | TYPE_MISMATCH",
          "$return[0] gt $return[1] | TYPE_MISMATCH",
          "$return.name == 'Jan' | ERASED_COLLECTION_TYPE"
        })
    void rejectsWhatHasNoTypeToTake(final String text, final Category category) {
      final ConditionException exception =
          assertThrows(ConditionException.class, () -> compileFor("unknown", text));

      assertEquals(category, exception.category(), exception.getMessage());
    }

    @Test
    void objectGetterIsDynamicToo() throws Exception {
      assertTrue(eval("$arg0.anything == 'dynamic'"));
      assertFalse(eval("$arg0.anything gt 1"));
    }

    @Test
    void staticOperandsAreNotCoerced() throws Exception {
      final ComparisonConditions.Equality typed =
          (ComparisonConditions.Equality) compile("$arg0.brand == 'bmw'");
      final ComparisonConditions.Equality dynamic =
          (ComparisonConditions.Equality) compile("$arg0.anything == 'bmw'");

      assertTrue(typed.left() instanceof ValueNode.PathNode);
      assertTrue(dynamic.left() instanceof ValueNode.Coerced);
    }
  }
}
