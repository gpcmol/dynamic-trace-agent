package com.example.binding;

/**
 * Application-like classes for binder tests. They live outside {@code org.otel.agent}, because
 * rules on the agent's own packages are rejected as protected.
 */
public final class BindingFixtures {

  private BindingFixtures() {}

  public interface Vehicle {
    String drive(Car car);
  }

  public static class Car implements Vehicle {
    public String getBrand() {
      return "bmw";
    }

    @Override
    public String drive(final Car car) {
      return "vroom";
    }

    public void park() {}

    void hidden() {}
  }

  public static final class SportsCar extends Car {
    @Override
    public String drive(final Car car) {
      return "VROOM";
    }
  }

  /** A final type: its properties are known exactly. */
  public record Result(boolean accepted) {}

  public static final class Garage {
    public Result park(final Car car) {
      return new Result(true);
    }
  }
}
