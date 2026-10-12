package org.otel.agent.core.path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.otel.agent.core.value.AttributeValues;

class PathReaderTest {

  public record Passenger(String name) {
    public String getName() {
      return name;
    }
  }

  public static final class Car {
    public final String plate;
    public static String staticField = "static";
    private final String brand;
    private final List<Passenger> passengers;
    private final Passenger[] seats;
    private final Map<String, String> tags = Map.of("a", "b");

    public Car(final String brand, final List<Passenger> passengers, final Passenger[] seats) {
      this.brand = brand;
      this.plate = "AB-12";
      this.passengers = passengers;
      this.seats = seats;
    }

    public String getBrand() {
      return brand;
    }

    public boolean isElectric() {
      return true;
    }

    public String isNotABooleanGetter() {
      return "no";
    }

    public List<Passenger> getPassengers() {
      return passengers;
    }

    public Passenger[] getSeats() {
      return seats;
    }

    public Map<String, String> getTags() {
      return tags;
    }

    @SuppressWarnings("DoNotCallSuggester") // throws on purpose: tests a failing getter
    public String getBroken() {
      throw new IllegalStateException("boom");
    }

    public static String getStaticValue() {
      return "static";
    }
  }

  public record Outcome(boolean accepted, String status) {}

  /** Public getter on a non-public class: needs the accessible fallback. */
  static final class Hidden {
    public String getSecret() {
      return "found";
    }
  }

  private static Object read(final String path, final Object root) throws InvalidPathException {
    return PathReader.ownedBy(PathCompiler.reference(path), PathReaderTest.class).readFrom(root);
  }

  private static final Car CAR =
      new Car(
          "bmw",
          List.of(new Passenger("Jan"), new Passenger("Piet")),
          new Passenger[] {new Passenger("Kees"), null});

  @Nested
  class Properties {

    @Test
    void getter() throws Exception {
      assertEquals("bmw", read("$this.brand", CAR));
    }

    @Test
    void booleanIsGetter() throws Exception {
      assertEquals(true, read("$this.electric", CAR));
    }

    @Test
    void isGetterMustReturnBoolean() throws Exception {
      assertNull(read("$this.notABooleanGetter", CAR));
    }

    @Test
    void recordComponentWithoutGetter() throws Exception {
      assertEquals("accepted", read("$this.status", new Outcome(true, "accepted")));
      assertEquals(true, read("$this.accepted", new Outcome(true, "accepted")));
    }

    @Test
    void publicField() throws Exception {
      assertEquals("AB-12", read("$this.plate", CAR));
    }

    @Test
    void staticMembersAreIgnored() throws Exception {
      assertNull(read("$this.staticField", CAR));
      assertNull(read("$this.staticValue", CAR));
    }

    @Test
    void missingPropertyIsNull() throws Exception {
      assertNull(read("$this.colour", CAR));
    }

    @Test
    void throwingGetterIsNull() throws Exception {
      assertNull(read("$this.broken", CAR));
    }

    @Test
    void publicGetterOfNonPublicClass() throws Exception {
      assertEquals("found", read("$this.secret", new Hidden()));
    }

    @Test
    void bareRootReturnsTheRoot() throws Exception {
      assertEquals(CAR, read("$this", CAR));
    }
  }

  @Nested
  class Nulls {

    @Test
    void nullRoot() throws Exception {
      assertNull(read("$this.brand", null));
    }

    @Test
    void nullInTheMiddle() throws Exception {
      assertNull(read("$this.passengers.name", new Car("x", null, null)));
    }

    @Test
    void mapIsNotTraversed() throws Exception {
      assertNull(read("$this.tags.a", CAR));
    }
  }

  @Nested
  class Indexing {

    @Test
    void listIndex() throws Exception {
      assertEquals("Piet", read("$this.passengers[1].name", CAR));
    }

    @Test
    void arrayIndex() throws Exception {
      assertEquals("Kees", read("$this.seats[0].name", CAR));
    }

    @Test
    void outOfRangeIsNull() throws Exception {
      assertNull(read("$this.passengers[5].name", CAR));
      assertNull(read("$this.seats[9].name", CAR));
    }

    @Test
    void nullElementIsNull() throws Exception {
      assertNull(read("$this.seats[1].name", CAR));
    }

    @Test
    void iterableIsIndexedByIteration() throws Exception {
      final Object holder =
          new Object() {
            @SuppressWarnings("unused")
            public Iterable<String> getNames() {
              return new LinkedHashSet<>(List.of("a", "b", "c"));
            }
          };
      // Anonymous classes are not public; the accessible fallback makes this work.
      assertEquals("c", read("$this.names[2]", holder));
    }
  }

  @Nested
  class Flattening {

    @Test
    void propertyOnListAppliesToEveryElement() throws Exception {
      assertEquals(List.of("Jan", "Piet"), read("$this.passengers.name", CAR));
    }

    @Test
    void propertyOnArraySkipsNullElements() throws Exception {
      assertEquals(List.of("Kees"), read("$this.seats.name", CAR));
    }

    @Test
    void nestedCollectionsAreFlattened() throws Exception {
      final List<Car> cars = List.of(CAR, CAR);
      final Object garage =
          new Object() {
            @SuppressWarnings("unused")
            public List<Car> getCars() {
              return cars;
            }
          };

      assertEquals(
          List.of("Jan", "Piet", "Jan", "Piet"), read("$this.cars.passengers.name", garage));
    }

    @Test
    void wholeCollectionAtTheEnd() throws Exception {
      assertTrue(read("$this.passengers", CAR) instanceof List<?>);
    }
  }

  @Nested
  class FrameRoots {

    @Test
    void readsFromTheFrame() throws Exception {
      final ExitFrame frame =
          new ExitFrame() {
            @Override
            public Object self() {
              return "self";
            }

            @Override
            public Object returned() {
              return CAR;
            }

            @Override
            public Object argument(final int index) {
              return index == 0 ? new Passenger("Eva") : null;
            }
          };

      assertEquals(
          "Eva",
          PathReader.ownedBy(PathCompiler.attributePath("$arg0.name"), PathReaderTest.class)
              .read(frame));
      assertEquals(
          "bmw",
          PathReader.ownedBy(PathCompiler.attributePath("$return.brand"), PathReaderTest.class)
              .read(frame));
      assertNull(
          PathReader.ownedBy(PathCompiler.attributePath("$arg3.name"), PathReaderTest.class)
              .read(frame));
    }
  }

  @Test
  void polymorphicTargetsUseTheirOwnReaders() throws Exception {
    final PathReader reader =
        PathReader.ownedBy(PathCompiler.attributePath("$this.name"), PathReaderTest.class);

    assertEquals("Jan", reader.readFrom(new Passenger("Jan")));
    assertEquals(
        "other",
        reader.readFrom(
            new Object() {
              @SuppressWarnings("unused")
              public String getName() {
                return "other";
              }
            }));
    assertEquals("Piet", reader.readFrom(new Passenger("Piet")));
  }

  @Nested
  class Safety {

    public static final class Order {
      private final AtomicInteger sequence = new AtomicInteger(41);

      public AtomicInteger getSequence() {
        return sequence;
      }

      /** Never ends, like a lazily loaded result. */
      public Iterable<Passenger> getStream() {
        return () -> Stream.generate(() -> new Passenger("Jan")).iterator();
      }

      public List<Passenger> getMany() {
        return Collections.nCopies(AttributeValues.MAX_LIST_SIZE + 500, new Passenger("Jan"));
      }
    }

    @Test
    void aPathCannotChangeApplicationState() throws Exception {
      final Order order = new Order();

      assertNull(read("$this.sequence.andIncrement", order));
      assertEquals(41, order.sequence.get());
    }

    @Test
    void endlessCollectionIsReadUpToTheLimit() throws Exception {
      final List<?> names = (List<?>) read("$this.stream.name", new Order());

      assertEquals(AttributeValues.MAX_LIST_SIZE, names.size());
    }

    @Test
    void largeCollectionIsTruncated() throws Exception {
      assertEquals(
          AttributeValues.MAX_LIST_SIZE, ((List<?>) read("$this.many.name", new Order())).size());
    }

    @Test
    void indexIntoAnEndlessCollectionIsBounded() throws Exception {
      assertEquals("Jan", read("$this.stream[10].name", new Order()));
      assertNull(read("$this.stream[999999999].name", new Order()));
    }

    @Test
    void indexIntoALargeListIsNotLimited() throws Exception {
      assertEquals(
          "Jan",
          read("$this.many[" + (AttributeValues.MAX_LIST_SIZE + 10) + "].name", new Order()));
    }
  }
}
