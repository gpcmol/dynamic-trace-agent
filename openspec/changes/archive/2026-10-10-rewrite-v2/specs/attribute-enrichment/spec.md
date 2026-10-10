## ADDED Requirements

### Requirement: Enrich the current span at method exit
On exit of a selected method the agent SHALL evaluate the rule's `<when>` (if present) and, when it
is true or absent, write the **global static attributes** and the rule's resolved dynamic
attributes on the current valid span, once per exit. It SHALL NOT create a span when a valid
current span exists.

#### Scenario: Existing span
- **WHEN** a selected method exits inside a valid span and the condition is true
- **THEN** that span receives the static and dynamic attributes exactly once for this exit

### Requirement: Fallback span
The agent SHALL create, enrich and end (in `finally`) exactly one `INTERNAL` span named
`dynamic-trace.enrichment` (tracer `dynamic-trace-agent`) when no valid current span exists.

#### Scenario: No active span
- **WHEN** a selected method exits with no current span
- **THEN** one INTERNAL span carrying the attributes is exported

### Requirement: Condition gates all work
A false `<when>` SHALL skip static attributes, dynamic attribute resolution, span lookup and
fallback-span creation for that exit.

#### Scenario: Condition false
- **WHEN** the condition evaluates to `false`
- **THEN** no span is touched or created

### Requirement: Re-entrancy guard
The agent SHALL skip nested enrichment on a thread that is already enriching (e.g. a getter that is
itself a selected method).

#### Scenario: Getter is selected
- **WHEN** resolving `$arg0.brand` calls `Car.getBrand`, which is also selected
- **THEN** the nested exit does not enrich and no recursion occurs

### Requirement: Never affect the application
Failures in resolution, evaluation or telemetry SHALL be contained per attribute; the application
method's result and exceptions SHALL be unchanged.

#### Scenario: Getter throws
- **WHEN** one property getter throws
- **THEN** that attribute is skipped, the other attributes are written, and the method returns normally

### Requirement: Attribute value conversion
Static attribute values SHALL be written as strings. Dynamic values SHALL be converted as follows:
`String`/`Boolean` as-is; `Byte`/`Short`/`Integer`/`Long` and `BigInteger` (if it fits) to long;
finite `Float`/`Double`/`BigDecimal` to double; `Character` to string; enum to `name()`; arrays and
`Iterable`s to a homogeneous OTel array (nulls dropped; typed empty array when the element type is
known). Any other type, non-finite numbers, overflow and mixed-type collections SHALL be skipped
(no `toString()` fallback).

#### Scenario: Int property
- **WHEN** `$arg0.year` is an `int`
- **THEN** the attribute is a long

#### Scenario: Arbitrary object
- **WHEN** `$arg0.customer` resolves to a `Customer` object
- **THEN** no attribute is written
