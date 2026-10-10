package com.example.engine;

import java.util.List;

/**
 * Application-like classes for extension tests. Outside {@code org.otel.agent}: rules on the
 * agent's own packages are rejected as protected.
 */
public final class Fixtures {

  private Fixtures() {}

  public record Passenger(String name) {}

  public static class Car {
    private final String brand;
    private final long mileage;
    private final List<Passenger> passengers;

    public Car(final String brand, final long mileage, final List<Passenger> passengers) {
      this.brand = brand;
      this.mileage = mileage;
      this.passengers = passengers;
    }

    public String getBrand() {
      return brand;
    }

    public long getMileage() {
      return mileage;
    }

    public List<Passenger> getPassengers() {
      return passengers;
    }

    public String getBroken() {
      throw new IllegalStateException("getter fails on purpose");
    }

    public String drive(final Car other) {
      return "vroom";
    }
  }

  public static final class SportsCar extends Car {
    public SportsCar(final String brand) {
      super(brand, 1, List.of());
    }

    @Override
    public String drive(final Car other) {
      return "VROOM";
    }
  }

  public record Result(boolean accepted, String status) {}

  public static final class Garage {
    public Result park(final Car car) {
      return new Result(true, "accepted");
    }

    public void wash(final Car car) {}

    public String many(
        final String a0, final String a1, final String a2, final String a3, final String a4) {
      return a4;
    }
  }

  /** Has a method with the same name as a selected method of another class. */
  public static final class Hotel {
    public String park(final Car car) {
      return "valet";
    }
  }
}
