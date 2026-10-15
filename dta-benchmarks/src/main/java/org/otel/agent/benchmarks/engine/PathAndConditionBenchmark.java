package org.otel.agent.benchmarks.engine;

import com.example.bench.BenchModel.Car;
import com.example.bench.BenchModel.Garage;
import com.example.bench.BenchModel.Passenger;
import com.example.bench.BenchModel.Result;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.otel.agent.core.dsl.Condition;
import org.otel.agent.core.dsl.ConditionTyper;
import org.otel.agent.core.dsl.ExitSignature;
import org.otel.agent.core.dsl.ExpressionParser;
import org.otel.agent.core.path.ExitFrame;
import org.otel.agent.core.path.PathCompiler;
import org.otel.agent.core.path.PathReader;

/** Building blocks of the hot path in isolation: path resolution and condition evaluation. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class PathAndConditionBenchmark {

  private final List<Passenger> passengers = List.of(new Passenger("Jan"), new Passenger("Piet"));
  private final Car bmw = new Car("bmw", "x5", 2024, 50_000, passengers);
  private final ExitFrame frame =
      new ExitFrame() {
        @Override
        public Object self() {
          return null;
        }

        @Override
        public Object returned() {
          return null;
        }

        @Override
        public Object argument(final int index) {
          return switch (index) {
            case 0 -> bmw;
            case 1 -> passengers;
            default -> null;
          };
        }
      };

  private PathReader brand;
  private PathReader secondPassenger;
  private PathReader indexedRoot;
  private Condition referenceCondition;
  private Condition genericElementCondition;
  private Condition dynamicCondition;

  @Setup
  public void setUp() throws Exception {
    brand = PathReader.ownedBy(PathCompiler.attributePath("$arg0.brand"), Garage.class);
    secondPassenger =
        PathReader.ownedBy(PathCompiler.attributePath("$arg0.passengers[1].name"), Garage.class);
    indexedRoot = PathReader.ownedBy(PathCompiler.attributePath("$arg1[1].name"), Garage.class);
    referenceCondition =
        ConditionTyper.type(
            ExpressionParser.parse(
                "$arg0.brand in ['bmw', 'audi', 'vw', 'mercedes', 'toyota', 'honda', 'ford',"
                    + " 'renault'] and $arg0.mileage gt 0"),
            new ExitSignature(Garage.class, List.of(Car.class), Result.class),
            Garage.class);
    // List<Passenger> getPassengers(): typed through the generic signature, no runtime coercion.
    genericElementCondition =
        ConditionTyper.type(
            ExpressionParser.parse("$arg0.passengers[1].name == 'Piet'"),
            new ExitSignature(Garage.class, List.of(Car.class, List.class), Result.class),
            Garage.class);
    // A raw List argument: the element is dynamic and coerced to String when evaluated.
    dynamicCondition =
        ConditionTyper.type(
            ExpressionParser.parse("$arg1[1].name == 'Piet'"),
            new ExitSignature(Garage.class, List.of(Car.class, List.class), Result.class),
            Garage.class);
  }

  @Benchmark
  public Object readSimpleProperty() {
    return brand.read(frame);
  }

  @Benchmark
  public Object readIndexedNestedProperty() {
    return secondPassenger.read(frame);
  }

  @Benchmark
  public Object readIndexedRoot() {
    return indexedRoot.read(frame);
  }

  @Benchmark
  public boolean evaluateReferenceCondition() {
    return Condition.evaluate(referenceCondition, frame);
  }

  @Benchmark
  public boolean evaluateGenericElementCondition() {
    return Condition.evaluate(genericElementCondition, frame);
  }

  @Benchmark
  public boolean evaluateDynamicCondition() {
    return Condition.evaluate(dynamicCondition, frame);
  }
}
