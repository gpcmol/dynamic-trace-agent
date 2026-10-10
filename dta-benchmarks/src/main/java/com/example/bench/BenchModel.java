package com.example.bench;

import java.util.List;

/**
 * Application-like classes for the engine benchmarks (outside {@code org.otel.agent}, which rules
 * may not select). Mirrors the garage demo.
 */
public final class BenchModel {

  private BenchModel() {}

  public record Passenger(String name) {}

  public record Result(boolean accepted, String status) {}

  public static final class Car {
    private final String brand;
    private final String model;
    private final int year;
    private final long mileage;
    private final List<Passenger> passengers;

    public Car(
        final String brand,
        final String model,
        final int year,
        final long mileage,
        final List<Passenger> passengers) {
      this.brand = brand;
      this.model = model;
      this.year = year;
      this.mileage = mileage;
      this.passengers = passengers;
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

    public List<Passenger> getPassengers() {
      return passengers;
    }
  }

  public static final class Garage {
    public Result park(final Car car) {
      return new Result(true, "accepted");
    }

    public Result inspect(final Car car) {
      return new Result(true, "inspected");
    }
  }
}
