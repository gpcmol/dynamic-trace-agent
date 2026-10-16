## MODIFIED Requirements

### Requirement: Maven build
The project SHALL build with Maven only (multi-module, e.g. `core`, `extension`, `example-app`,
`dist`; exact split to be finalised in design). Gradle SHALL NOT be used. The build SHALL require
JDK 21 or newer. Main code SHALL be compiled for a Java 17 runtime (`--release 17`); test code MAY
use Java 21.

#### Scenario: Clean build
- **WHEN** `mvn clean verify` runs on a machine with JDK 21
- **THEN** all modules build and all tests pass

#### Scenario: Java 21 API in main code
- **WHEN** main code uses a language feature or JDK API that is not in Java 17
- **THEN** the build fails

#### Scenario: Extension jar class file version
- **WHEN** the classes in `dynamic-trace-agent.jar` are inspected
- **THEN** none has a class file version newer than 61 (Java 17)

### Requirement: Supported OTel agent versions
The project SHALL document the OTel Java agent versions and the Java runtime versions it is tested
against and SHALL run its integration tests in CI against each supported agent version, with the
application on each supported Java runtime (at least 17 and 21). Documentation SHALL recommend
pinning the agent image tag instead of `latest`, because the extension uses agent APIs.

#### Scenario: New agent release
- **WHEN** a new OTel Java agent version is added to the CI matrix and an agent API used by the
  extension changed
- **THEN** CI fails before the combination reaches production

#### Scenario: Application on Java 17
- **WHEN** the instrumented application runs on Java 17 with the OTel agent and the extension
- **THEN** the extension loads and enriches spans exactly as on Java 21

#### Scenario: Requirements in the README
- **WHEN** a user reads the README
- **THEN** it states the minimum Java runtime of the application, the tested agent version(s) and
  the JDK needed to build, separately
