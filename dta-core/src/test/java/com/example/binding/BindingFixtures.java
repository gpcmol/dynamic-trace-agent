package com.example.binding;

import java.util.List;

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

    /** Array return: the element type is known. */
    public Result[] history() {
      return new Result[] {new Result(true)};
    }

    /** Generic list return: the element type comes from the generic signature. */
    public List<Result> recent() {
      return List.of(new Result(false));
    }
  }

  /** Declares {@code List<T>}: the element type is only known on a subclass. */
  public abstract static class Shelf<T> {
    public abstract List<T> items();
  }

  public static final class ResultShelf extends Shelf<Result> {
    @Override
    public List<Result> items() {
      return List.of(new Result(true));
    }
  }

  /** Inherits {@code List<T> items()} without overriding it. */
  public static final class InheritedShelf extends Base<Result> {}

  public static class Base<T> {
    private final List<T> items = new java.util.ArrayList<>();

    public List<T> items() {
      return items;
    }
  }
}
