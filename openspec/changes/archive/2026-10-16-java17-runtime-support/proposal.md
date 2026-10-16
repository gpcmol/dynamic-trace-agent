## Why

The extension jar is compiled with `--release 21`, so an application running on Java 17 cannot load
it (`UnsupportedClassVersionError`) and gets no enrichment at all. The extension runs inside the
application's JVM, so the application decides the Java version, not this project; Java 17 is still
the baseline of much of the production landscape (Spring Boot 3). The README does not state a runtime
requirement at all, only the JDK needed to build.

## What Changes

- Main code of all modules is compiled with `--release 17`; test code stays on `--release 21`.
  Building still requires JDK 21 or newer.
- The five pattern-matching `switch` statements over sealed types (`RuleBinder`, `ConditionTyper`
  ×2, `SnapshotCompiler` ×2) and the two `List.getFirst()` calls (`AttributeValues`) are replaced by Java 17
  equivalents. Behaviour is unchanged.
- The integration tests can start the instrumented application on another JVM
  (`-Dit.java.home=…`); CI runs them with the application on Java 17 and on Java 21.
- README gets a "Compatibility" section: application runtime Java 17+, tested OTel agent version,
  build JDK.
- OpenTelemetry Java agent 2.30.0 → 2.32.0 (with the matching extension API, proto and other
  dependency updates via `lcm.sh`).
- GitHub Actions and Docker base images updated to their latest releases, pinned by commit SHA /
  exact tag.
- Review of null handling and hot-path performance of the touched code.

## Capabilities

### New Capabilities

(none)

### Modified Capabilities

- `packaging-and-distribution`: the build targets a Java 17 runtime instead of Java 21; supported
  Java runtimes are documented and tested in CI next to the supported agent versions.

## Impact

- `pom.xml` (compiler release, test release, versions), all `dta-*` main code compiles for 17.
- `dta-core`: `RuleBinder`, `ConditionTyper`, `SnapshotCompiler`, `AttributeValues`.
- `dta-integration-tests`: `InstrumentedApp`, `pom.xml`.
- `.github/workflows/*.yml`, `Dockerfile`, `dta-example-app/Dockerfile`, `README.md`,
  `deploy/kubernetes/instrumentation.yaml`, `docs/10-code-guidelines.md`, `docs/03-classloaders.md`.
- No change to the rule file format, the gateway contract or runtime behaviour.
