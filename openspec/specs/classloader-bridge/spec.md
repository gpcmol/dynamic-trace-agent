# classloader-bridge Specification

## Purpose
TBD - created by archiving change rewrite-v2. Update Purpose after archive.
## Requirements
### Requirement: One explicit boundary
The agent SHALL route all communication between the extension classloader and advice code inlined
in application classloaders through one narrow, documented gateway type. No other class
SHALL be shared across the boundary, and reflection across the boundary SHALL NOT be used on the
hot path.

#### Scenario: Developer reads the code
- **WHEN** a developer searches for how config reaches advice
- **THEN** exactly one gateway package contains the whole mechanism and links to the design doc

### Requirement: Bootstrap-visible gateway
The gateway type SHALL be loadable by every application classloader and SHALL reference JDK types
only. The extension SHALL publish it itself, without an extra `-javaagent` and without writing files
to disk (pods may run with a read-only root filesystem): the gateway classes SHALL be embedded in the
extension jar outside the package path and defined directly in the bootstrap classloader, and the
gateway package SHALL be registered for boot delegation so that classloaders which do not delegate
to bootstrap by default (OSGi, JBoss Modules) still resolve it.

#### Scenario: Multiple application classloaders
- **WHEN** an application uses several classloaders (e.g. an application server)
- **THEN** advice in each of them reaches the same gateway

#### Scenario: Read-only root filesystem
- **WHEN** the pod runs with `readOnlyRootFilesystem: true`
- **THEN** the gateway is installed and enrichment works

#### Scenario: Classloader that only delegates java.*
- **WHEN** an application classloader delegates only `java.*` to its parent
- **THEN** it still resolves the gateway from bootstrap

#### Scenario: Extension never loads its own gateway copy
- **WHEN** the extension classloader resolves a gateway type
- **THEN** it obtains the bootstrap class (the extension jar contains no loadable copy)

### Requirement: Name-level plan separate from class-bound rules
The transformation plan used by Byte Buddy matchers SHALL be expressed in class and method
**names** only, so it can be built without loading application classes. Rules bound to real
`Class` objects (resolved accessors, type-checked conditions) SHALL be built per application
classloader once the selected class is loadable.

#### Scenario: Config loaded before application classes
- **WHEN** the configuration is read before the application classloader can load `com.example.Garage`
- **THEN** `Garage.park` is still instrumented when it loads, and its rules bind on first use
  (no timed retry or deferred-load workaround)

### Requirement: Immutable typed snapshot, no serialization
Runtime rule data SHALL reach advice as immutable, typed objects published through a volatile
reference. Serialising rules into strings or system properties SHALL NOT be used.

#### Scenario: Reload during traffic
- **WHEN** a new snapshot is published while calls are in flight
- **THEN** each call observes either the old or the new snapshot completely, never a mix

### Requirement: No classloader leaks
Data that refers to application classes SHALL NOT be strongly reachable from the gateway or the
extension; an undeployed application classloader SHALL be collectable.

#### Scenario: Web application undeployed
- **WHEN** an application classloader is discarded
- **THEN** after GC no agent structure retains it (verified by a test)

### Requirement: Fail-open without engine
If the gateway has no engine registered (extension not loaded, disabled, or failed), advice SHALL
return immediately without error.

#### Scenario: Engine not registered
- **WHEN** advice runs before or without the engine
- **THEN** the application method is unaffected

### Requirement: Hot-path budget
The advice → gateway → rules path SHALL use no per-call map lookups by name and SHALL identify the
instrumented method by a constant baked into the advice. Methods whose rules use at most `$arg3`
SHALL pass `$this`, `$return` and the first four arguments individually (no argument array); only
methods whose rules use `$arg4` or higher SHALL pass an argument array. The path SHALL be measured by
the benchmarking capability.

#### Scenario: Condition false without escape analysis
- **WHEN** a selected method with rules on `$arg0` exits, its condition is false, and the JVM runs
  with `-XX:-DoEscapeAnalysis`
- **THEN** JMH reports 0 B/op for passing object arguments

#### Scenario: Disabled configuration
- **WHEN** no rules are active
- **THEN** no advice is installed at all (zero per-call cost)

