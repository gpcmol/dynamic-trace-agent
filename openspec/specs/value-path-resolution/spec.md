# value-path-resolution Specification

## Purpose
TBD - created by archiving change rewrite-v2. Update Purpose after archive.
## Requirements
### Requirement: Path roots
A path SHALL start with `$this` (receiver), `$argN` (N in 0..127) or `$return` (return value;
`null` for void methods). The root MAY be followed directly by one `[index]` (non-negative decimal
int), the root index. Then follow zero or more dot-separated property segments. A segment is a Java
identifier with an optional single `[index]` (non-negative decimal int). An attribute path SHALL
have at least one property segment or a root index; a condition reference MAY be a bare root.

#### Scenario: Nested indexed path
- **WHEN** the path is `$arg0.passengers[1].name`
- **THEN** it is compiled once into segments and resolves the name of the second passenger

#### Scenario: Indexed root followed by a property
- **WHEN** the path is `$return[0].color` and the method returns a `List<Tree>`
- **THEN** it resolves the color of the first tree in the returned list

#### Scenario: Indexed argument root
- **WHEN** the path is `$arg1[2].name` and the second argument is an array of passengers
- **THEN** it resolves the name of the third passenger

#### Scenario: Indexed root only
- **WHEN** the attribute path is `$return[0]` and the method returns `List<String>`
- **THEN** the value is the first string of the list

#### Scenario: Root without property
- **WHEN** the attribute path is `$arg0`
- **THEN** the configuration is rejected (a property segment or root index is required)

### Requirement: Compile once
Paths SHALL be compiled at configuration load into immutable segments; the original string SHALL
NOT be used on the hot path.

#### Scenario: Invalid path
- **WHEN** a path has an empty segment, unknown root, `$arg` index outside 0..127, a non-numeric
  or doubled index, or a malformed bracket
- **THEN** the configuration is rejected

#### Scenario: Invalid root index
- **WHEN** a path is `$return[]`, `$return[01]`, `$return[-1]`, `$return[0][1]`, `$return[0]x`,
  `$return[0].` or `$arg0[1000000000]`
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

### Requirement: Protected types
A path SHALL NOT be able to reach the JVM itself or change application state. The property `class`
SHALL be refused when the path is compiled. Values whose type is, extends or implements one of the
protected JDK types — `Class`, `ClassLoader`, `Module`, `ModuleLayer`, `Thread`, `ThreadGroup`,
`Runtime`, `Process`, `ProcessBuilder`, `ProcessHandle`, reflection and method-handle objects,
`ProtectionDomain`, streams, readers, writers, channels, sockets, `URLConnection`, JDBC objects,
`Key`, `KeyStore`, `AtomicInteger`, `AtomicLong` — SHALL have no properties.

#### Scenario: Class property
- **WHEN** a path contains the segment `class` (e.g. `$this.class.classLoader`)
- **THEN** the path is invalid

#### Scenario: State-changing getter
- **WHEN** `$this.sequence.andIncrement` is resolved and `sequence` is an `AtomicInteger`
- **THEN** the result is `null` and the counter is unchanged

### Requirement: Bounded paths and collections
A path SHALL have at most 32 segments. Of an array or `Iterable` at most 1 000 elements SHALL be
read, so that a huge, endless or lazily loaded collection cannot stall the application thread; a
resulting list SHALL have at most 1 000 elements.

#### Scenario: Endless collection
- **WHEN** `$arg0.stream.name` is resolved and `stream` is an `Iterable` that never ends
- **THEN** the result is the list of the first 1 000 names

### Requirement: Indexing
An indexed segment SHALL select element `index` from an array, a `List`, or (by iteration) any
other `Iterable`; an out-of-range index resolves to `null`. An `Iterable` that is not a
`Collection` is iterated for at most 1 000 elements. A root index SHALL select from the root value
with exactly the same rules; a root value that is `null`, a `Map` or not an array or `Iterable`
resolves to `null`.

#### Scenario: Index out of range
- **WHEN** `$arg0.passengers[5].name` is resolved and there are 2 passengers
- **THEN** the result is `null`

#### Scenario: Root index out of range
- **WHEN** `$return[3].color` is resolved and the returned list has 2 trees
- **THEN** the result is `null`

#### Scenario: Root index on an empty or null return value
- **WHEN** `$return[0].color` is resolved and the method returns an empty list or `null`
- **THEN** the result is `null` and no exception escapes

#### Scenario: Root index on a non-collection
- **WHEN** `$return[0].color` is resolved and the return value is a `Tree`
- **THEN** the result is `null`

#### Scenario: Root index on an endless iterable
- **WHEN** `$arg0[5000].name` is resolved and `$arg0` is an `Iterable` that never ends
- **THEN** at most 1 000 elements are iterated and the result is `null`

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
resolved at runtime and a missing property SHALL resolve to `null`. After a root index the check
SHALL continue with the component type of an array root and stop for any other root type.
Conditions are always type-checked (see condition-dsl).

#### Scenario: Non-final declared type
- **WHEN** the path is `$arg0.colour`, the parameter type `Car` is not final and has no `colour`
- **THEN** the rule stays active and the attribute is resolved at runtime (absent → not written)

#### Scenario: Erased list element
- **WHEN** the path is `$arg0.passengers[1].name` and `passengers` is a `List<Passenger>`
- **THEN** `name` is resolved on the runtime element; a missing property yields `null`, not a warning

#### Scenario: Erased list root
- **WHEN** the path is `$return[0].colour` and the method returns `List<Tree>`
- **THEN** the rule stays active; `colour` is resolved on the runtime element (absent → not written)

#### Scenario: Array root of a final type
- **WHEN** the path is `$return[0].colour`, the method returns `Tree[]` and the record `Tree` has
  no `colour`
- **THEN** the rule is ignored for that class with one warning naming the attribute and property

### Requirement: Null safety
Resolution SHALL return `null` for a missing argument, a null intermediate, a `Map` intermediate, a
missing property, or a throwing getter; no exception SHALL escape. A `null` dynamic attribute value SHALL be
skipped.

#### Scenario: Null in the middle
- **WHEN** `$arg0.customer` is null for `$arg0.customer.country`
- **THEN** the result is `null` and no exception escapes

