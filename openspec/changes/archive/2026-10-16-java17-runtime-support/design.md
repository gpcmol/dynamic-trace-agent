## Context

The OTel Java agent runs on Java 8+, but our extension jar has class file version 65 (Java 21). The
agent loads extensions with its own classloader; on a Java 17 JVM loading fails and the application
runs without enrichment. A trial compile of the main code with `--release 17` fails on five
pattern-matching switches over the sealed `PathRoot`, `Expression` and `Operand` (javac reports
them in phases) and two `List.getFirst()` calls; records, sealed types and `instanceof` patterns are Java 17 and stay.

## Goals / Non-Goals

**Goals:**
- The extension jar (and the example app) run on Java 17 and newer.
- CI proves it: the end-to-end tests run with the application on Java 17 and on Java 21.
- The README states runtime and build requirements separately.

**Non-Goals:**
- Java 11 or 8. Records and sealed types are used throughout; that would be a rewrite.
- Building on JDK 17. Error Prone, the formatter and the tests keep JDK 21 as build baseline.

## Decisions

1. **`maven.compiler.release=17`, `maven.compiler.testRelease=21`.** Only the shipped code is
   constrained; tests keep Java 21 language features. `--release` (not `-source/-target`) also checks
   the JDK API, so a Java 21-only method in main code fails the build. Alternative — multi-release
   jar — adds complexity for no gain, the code needs no Java 21 API.

2. **Switches become `instanceof` chains ending in `throw new IllegalStateException(...)`.** The sealed
   hierarchies are small and closed. We lose the compiler's exhaustiveness check; alternatives were
   a visitor or an abstract method on each sealed type, but that moves binder/typer/compiler logic
   into the model types (`core.dsl`, `core.path`) and changes many files. Mitigation: existing tests
   cover every subtype; a new subtype added without a branch fails those tests with an exception
   that names the value. `IllegalStateException`, not `AssertionError`: the switch threw
   `MatchException`/`NullPointerException` (both `RuntimeException`), and the bind/reload
   boundaries (`ClassBindings`, `Retransformer`) catch `RuntimeException | LinkageError`, not
   `Error`. All five are cold-path code (load and bind), so the chain
   has no performance relevance.

3. **`getFirst()` → `get(0)`.** Equivalent on the non-empty `ArrayList` it is called on.

4. **App JVM for integration tests via `it.java.home`.** `InstrumentedApp` uses
   `System.getProperty("it.java.home", java.home)`. The tests themselves still run on the build JDK.
   CI installs JDK 17 and 21 with one `setup-java` step (the last one becomes `JAVA_HOME`) and gets
   the 17 path from the `JAVA_HOME_17_X64` environment variable that `setup-java` exports. A matrix
   axis `app-java: [17, 21]` crosses with `otel-agent`.

5. **Enforcer keeps `requireJavaVersion [21,)`** for the build; the README says "build: JDK 21+,
   run: Java 17+".

6. **Dependencies.** `./lcm.sh` (minor updates) brings the agent to 2.32.0 and updates the pins in
   CI, README and the Kubernetes example. Major updates (JUnit 6, Jackson 3, …) are reviewed one by
   one and only taken when the build stays green without code churn: JUnit 6.1.3 and Spotless
   3.10.4 are taken; google-java-format 1.37.0 is not (fails on Spotless 3.10.4 and reformats ~10
   files), it stays on 1.35.0. Error Prone 2.50 needs `-XDaddTypeAnnotationsToSymbol=true` on
   JDK 21. GitHub Actions: take the major
   versions Dependabot proposed (verified against the upstream tags) plus newer patches; Docker base
   images: latest patch of the same line.

## Risks / Trade-offs

- [Exhaustiveness lost] → `IllegalStateException` naming the value, plus unit tests for every
  subtype.
- [Stale class files after changing `release`] → the compiler plugin does not recompile unchanged
  sources; `PackagingIT` checks the class file version of every class in the jar (CI builds clean).
- [Agent 2.32.0 changes an internal API the extension uses] → the integration tests run against the
  real agent (`InstrumentationHolder`, `BootstrapPackagesConfigurer`); CI fails before release.
- [Byte Buddy / agent behaviour differs on Java 17] → the Java 17 leg of the CI matrix.
- [`autoinstrumentation-java:2.32.0` image missing] → checked before release (guidelines §6).

## Migration Plan

Drop-in: the new jar replaces the old one; nothing changes for Java 21+ users. Rollback: previous
image tag.

## Open Questions

None.
