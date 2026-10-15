## ADDED Requirements

### Requirement: Indexed root references
A reference in a condition SHALL accept a root index (`$return[0].color`, `$arg0[1]`). Its static type
SHALL follow the same rules as an indexed segment: the component type of an array root; for a
`List` or other `Iterable` root the element type is unknown and compilation SHALL fail with
category `erased collection type`; any other root type SHALL fail with category `type mismatch`.
At runtime the root index SHALL select the element as specified in value-path-resolution.

#### Scenario: Array root in a condition
- **WHEN** the method returns `Tree[]` and the condition is `$return[0].color == 'green'`
- **THEN** it compiles and is `true` when the first tree is green

#### Scenario: Generic list root in a condition
- **WHEN** the method returns `List<Tree>` and the condition is `$return[0].color == 'green'`
- **THEN** compilation fails with category `erased collection type` and the rule is disabled

#### Scenario: Index on a non-collection root
- **WHEN** the first parameter is a `Car` and the condition is `$arg0[0].brand == 'bmw'`
- **THEN** compilation fails with category `type mismatch`

#### Scenario: Out-of-range root index
- **WHEN** the method returns `Tree[]` of length 1 and the condition is `$return[1] == null`
- **THEN** it evaluates to `true`
