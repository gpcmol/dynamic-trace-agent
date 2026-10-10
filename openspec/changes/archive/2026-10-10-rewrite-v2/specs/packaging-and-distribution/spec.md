## ADDED Requirements

### Requirement: Maven build
The project SHALL build with Maven only (multi-module, e.g. `core`, `extension`, `example-app`,
`dist`; exact split to be finalised in design). Gradle SHALL NOT be used. Target Java 21.

#### Scenario: Clean build
- **WHEN** `mvn clean verify` runs on a machine with JDK 21
- **THEN** all modules build and all tests pass

### Requirement: Core has no runtime dependencies
The `core` module (configuration model, XML parser, DSL, path resolution, plans) SHALL depend on
the JDK only, so it is testable without an agent or Byte Buddy.

#### Scenario: Dependency check
- **WHEN** the core module's dependency tree is inspected
- **THEN** it has no compile or runtime dependencies

### Requirement: Single extension artifact
The build SHALL produce exactly one deployable artifact: the OTel extension jar (shaded, SPI merged
via AutoService). The full feature set, including retransformation on reload, SHALL work with only
the OTel Java agent plus this extension; no additional `-javaagent` SHALL be required.

#### Scenario: OpenTelemetry Operator deployment
- **WHEN** a pod is instrumented by an `Instrumentation` CR with `java.extensions` pointing at the
  extension image (`dir: /extensions`)
- **THEN** the extension is discovered and active, and adding/removing a rule for an already-loaded
  class takes effect without restart

#### Scenario: Plain JVM
- **WHEN** the JVM starts with `-javaagent:opentelemetry-javaagent.jar` and
  `-Dotel.javaagent.extensions=<extension.jar>`
- **THEN** the behaviour is identical to the Operator deployment

### Requirement: Supported OTel agent versions
The project SHALL document the OTel Java agent versions it is tested against and SHALL run its
integration tests in CI against each supported version. Documentation SHALL recommend pinning the
agent image tag instead of `latest`, because the extension uses agent APIs.

#### Scenario: New agent release
- **WHEN** a new OTel Java agent version is added to the CI matrix and an agent API used by the
  extension changed
- **THEN** CI fails before the combination reaches production

### Requirement: Docker image and CI
The project SHALL publish a Docker image containing `/extensions/dynamic-trace-agent.jar` on a minimal
base that provides `cp` (required by the Operator's copy init container, e.g. busybox) to GHCR with
a mutable `snapshot` tag for branch builds, a `vX.Y.Z` plus `latest` tag per release, and
retention of the five newest release versions.

#### Scenario: Release tag
- **WHEN** a `v*.*.*` tag is pushed
- **THEN** the image is pushed with the version tag and `latest`

### Requirement: Code quality gates
The build SHALL enforce: formatting with Spotless (google-java-format), static analysis with Error
Prone, and architecture rules with ArchUnit (no package cycles, layer rules such as "core depends
on the JDK only"). The code SHALL follow `docs/10-code-guidelines.md`.

#### Scenario: Package cycle
- **WHEN** a change introduces a package cycle
- **THEN** the ArchUnit test fails the build

#### Scenario: Unformatted code
- **WHEN** a file is not google-java-format formatted
- **THEN** `mvn verify` fails
