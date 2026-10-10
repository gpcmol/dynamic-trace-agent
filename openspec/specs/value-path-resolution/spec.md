# value-path-resolution Specification

## Purpose
TBD - created by archiving change rewrite-v2. Update Purpose after archive.
## Requirements
### Requirement: Path roots
A path SHALL start with `$this` (receiver), `$argN` (N in 0..127) or `$return` (return value;
`null` for void methods), followed by one or more dot-separated property segments. A segment is a
Java identifier with an optional single `[index]` (non-negative decimal int).

#### Scenario: Nested indexed path
- **WHEN** the path is `$arg0.passengers[1].name`
- **THEN** it is compiled once into segments and resolves the name of the second passenger

#### Scenario: Root without property
- **WHEN** the path is `$arg0`
- **THEN** the configuration is rejected (a property segment is required)

### Requirement: Compile once
Paths SHALL be compiled at configuration load into immutable segments; the original string SHALL
NOT be used on the hot path.

#### Scenario: Invalid path
- **WHEN** a path has an empty segment, unknown root, `$arg` index outside 0..127, a non-numeric
  or doubled index, or a malformed bracket
- **THEN** the configuration is rejected

### Requirement: Property access
A property SHALL be read via a public no-arg instance getter `getX()`, then a public `isX()`
returning `boolean`/`Boolean`, then the accessor `x()` of a record component, then a public instance field `x` (declared or
inherited). Accessors
SHALL be discovered once per (runtime class, property) and invoked without per-call reflection.
Maps, static members, non-public members and arbitrary method calls SHALL NOT be supported.

#### Scenario: Boolean property
- **WHEN** a class has `isAccepted()` and no `getAccepted()`
- **THEN** `$return.accepted` resolves via `isAccepted()`

#### Scenario: Record component
- **WHEN** `$return` is `record Result(boolean accepted, String status)` without getters
- **THEN** `$return.status` resolves via the accessor `status()`

### Requirement: Indexing
An indexed segment SHALL select element `index` from an array, a `List`, or (by iteration) any
other `Iterable`; an out-of-range index resolves to `null`.

#### Scenario: Index out of range
- **WHEN** `$arg0.passengers[5].name` is resolved and there are 2 passengers
- **THEN** the result is `null`

### Requirement: Collection flattening
When a non-indexed segment is applied to an array or `Iterable`, the remaining path SHALL be
resolved for each element and all non-null results collected into one flat list (nested
collections are flattened).

#### Scenario: All passenger names
- **WHEN** the path is `$arg0.passengers.name`
- **THEN** the result is the list of all non-null passenger names

### Requirement: Static property check where the type is known
The agent SHALL check each attribute path property against its static type when the class binds,
if that type is exactly known (a final class such as a record); a property that does not exist
there SHALL make the rule ignored with one warning (see xml-configuration). For non-final types (a
subclass may add the property), `Object`, arrays, collections and maps, the property SHALL be
resolved at runtime and a missing property SHALL resolve to `null`. Conditions are always
type-checked (see condition-dsl).

#### Scenario: Non-final declared type
- **WHEN** the path is `$arg0.colour`, the parameter type `Car` is not final and has no `colour`
- **THEN** the rule stays active and the attribute is resolved at runtime (absent → not written)

#### Scenario: Erased list element
- **WHEN** the path is `$arg0.passengers[1].name` and `passengers` is a `List<Passenger>`
- **THEN** `name` is resolved on the runtime element; a missing property yields `null`, not a warning

### Requirement: Null safety
Resolution SHALL return `null` for a missing argument, a null intermediate, a `Map` intermediate, a
missing property, or a throwing getter; no exception SHALL escape. A `null` dynamic attribute value SHALL be
skipped.

#### Scenario: Null in the middle
- **WHEN** `$arg0.customer` is null for `$arg0.customer.country`
- **THEN** the result is `null` and no exception escapes

