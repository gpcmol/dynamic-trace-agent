## 1. Java 17 target

- [x] 1.1 Root `pom.xml`: `maven.compiler.release` 17, `maven.compiler.testRelease` 21, pass `testRelease` to the compiler plugin
- [x] 1.2 `RuleBinder.rootType`: pattern switch over `PathRoot` → `instanceof` chain ending in `IllegalStateException`
- [x] 1.3 `ConditionTyper.condition`, `value`, `rootType`: pattern switches over `Expression`, `Operand`, `PathRoot` → same
- [x] 1.4 `SnapshotCompiler.maxArgumentIndex` (`Expression`, `Operand`): same
- [x] 1.5 `AttributeValues`: `getFirst()` → `get(0)`
- [x] 1.6 Compile all main code with `--release 17` and fix any further Java 21-only usage
- [x] 1.7 Test: no class in the extension jar has a class file version above 61 (`PackagingIT`)

## 2. Integration tests on Java 17

- [x] 2.1 `InstrumentedApp`: start the application with `it.java.home` (default: `java.home`)
- [x] 2.2 `dta-integration-tests/pom.xml`: document `-Dit.java.home` (failsafe passes user properties on)
- [x] 2.3 Run `mvn verify` with the application on JDK 17 locally

## 3. Dependencies

- [x] 3.1 `./lcm.sh`: OTel agent 2.32.0 and other minor updates; pins in CI, README, Kubernetes example
- [x] 3.2 Review major updates: JUnit 6.1.3 and Spotless 3.10.4 taken; google-java-format stays 1.35.0
- [x] 3.3 GitHub Actions to latest releases (SHA-pinned, verified against tags)
- [x] 3.4 Docker base images to latest patch (`maven`, `busybox`, `eclipse-temurin`)
- [x] 3.5 Error Prone 2.50: add `-XDaddTypeAnnotationsToSymbol=true`

## 4. CI

- [x] 4.1 `ci.yml`: install JDK 17 + 21, matrix `app-java: [17, 21]` × `otel-agent`, pass `-Dit.java.home`

## 5. Review

- [x] 5.1 Null checks in the changed code and its callers
- [x] 5.2 Hot-path performance check (JMH before/after, Java 17 and 21; `benchmarks/jmh-2026-10-16.md`)

## 6. Documentation

- [x] 6.1 README: Compatibility section (runtime Java 17+, agent 2.32.0, build JDK 21+)
- [x] 6.2 `docs/10-code-guidelines.md`: Java 17 main-code rule; `docs/03-classloaders.md` agent version note
- [x] 6.3 `openspec validate java17-runtime-support`
