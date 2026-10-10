# selector-instrumentation Specification

## Purpose
TBD - created by archiving change rewrite-v2. Update Purpose after archive.
## Requirements
### Requirement: Advice only on selected methods
Byte Buddy advice SHALL be applied only to methods selected by the compiled transformation plan,
matched per `(type, method name)` pair. Constructors, static, abstract, native, bridge and
synthetic methods SHALL be excluded. Unselected methods SHALL carry no advice.

#### Scenario: Unselected method
- **WHEN** a configured class has methods besides the selected one
- **THEN** only the selected method's bytecode is changed

#### Scenario: Same method name on another configured class
- **WHEN** rules select `Garage.park` and `Car.getBrand`
- **THEN** `Car.park` (if it exists) and `Garage.getBrand` receive no advice

### Requirement: Hierarchy matching
A selector SHALL also match subclasses and implementations (`hasSuperType` /
`isAssignableFrom` semantics), including when the selector names an interface or abstract class.

#### Scenario: Subclass
- **WHEN** a rule selects `Car.drive` and `SportsCar extends Car` overrides `drive`
- **THEN** `SportsCar.drive` is instrumented and the rule applies

### Requirement: Each matching selector keeps its own condition
The agent SHALL evaluate each matching selector with its own rule's condition and attributes when
several selectors match the same runtime method (e.g. one on `Car.drive`, one on `SportsCar.drive`).

#### Scenario: Two rules, two conditions
- **WHEN** both rules match and only the second rule's condition is true
- **THEN** only the second rule's attributes are written

### Requirement: One advice for void and value methods
The same advice SHALL serve void and value-returning methods, using a dynamically typed return
parameter that is `null` for void methods.

#### Scenario: Void method
- **WHEN** the selected method is `void`
- **THEN** `$return` resolves to `null`

### Requirement: Transformation fingerprint
The plan SHALL expose a stable fingerprint of the selected footprint: the set of `(class, method)`
pairs plus, per pair, whether the advice passes all arguments (needed when a rule reads `$arg4` or
higher). It SHALL be independent of all other `<when>` and attribute content and is used by reload
to decide on retransformation.

#### Scenario: Only attributes changed
- **WHEN** a reload changes only attributes or `<when>` and no rule starts reading `$arg4` or higher
- **THEN** the fingerprint is unchanged and no class is retransformed

#### Scenario: Rule starts reading a high argument
- **WHEN** a reload changes an attribute path from `$arg0.x` to `$arg5.x`
- **THEN** the fingerprint changes and the selected classes are retransformed

### Requirement: Agent isolation
The agent SHALL reject selectors whose class is in a protected namespace: `java.*`, `javax.*`,
`jdk.*`, `sun.*`, `com.sun.*`, `io.opentelemetry.*`, `net.bytebuddy.*` and the agent's own
packages. The rejection SHALL disable only the affected rule and be reported once.

#### Scenario: JDK class configured
- **WHEN** a selector names `java.util.ArrayList`
- **THEN** that rule is disabled with one warning and the rest of the configuration stays active

