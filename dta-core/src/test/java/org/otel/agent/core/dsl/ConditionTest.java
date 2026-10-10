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
          "$arg0.passengers[1].name == 'Jan' | ERASED_COLLECTION_TYPE",
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
}
