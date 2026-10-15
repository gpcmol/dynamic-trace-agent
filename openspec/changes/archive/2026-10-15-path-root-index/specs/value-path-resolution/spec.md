## MODIFIED Requirements

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
