# condition-dsl Specification

## Purpose
TBD - created by archiving change rewrite-v2. Update Purpose after archive.
## Requirements
### Requirement: XML-safe operators
The language SHALL support `or`, `and`, `not`, `==`, `!=`, `lt`, `lte`, `gt`, `gte`, `in`, `+`,
`-`, `*`, `/`, `%` and parentheses, so an expression needs no XML escaping. Symbolic forms
`&&`, `||`, `!`, `<`, `>`, `<=`, `>=` SHALL be rejected.

#### Scenario: Symbolic operator
- **WHEN** `<when>` contains `&&`
- **THEN** compilation fails with category `syntax error`

### Requirement: Literals, functions and bare booleans
The language SHALL support literals: strings (`'x'`/`"x"`), long/double numbers, `true`, `false`,
`null`, collection literals `[a, b]` (only as right operand of `in`); and the functions `size`,
`contains`, `like`, `ilike`, `icontains`. A bare boolean property (e.g. `$arg0.electric`) SHALL be
accepted as a condition meaning `== true`. The literal expression `true` means unconditional.

#### Scenario: Case-insensitive prefix
- **WHEN** the expression is `ilike($arg0.model, 'x%')` and the model is `X5`
- **THEN** it evaluates to `true`

### Requirement: Static typing
Expressions SHALL be type-checked once at load against the selected class and the selected
method's parameter/return types. Integral types normalise to long and floating types to double.
`==`/`!=` require equal normalised types (or a `null` literal); `lt`/`lte`/`gt`/`gte` require
numeric operands; `like`/`ilike` require strings; `contains`/`icontains` take a String or a
Collection haystack and a String needle; `in` elements must match the value's normalised type.

#### Scenario: Type mismatch
- **WHEN** the expression is `$arg0.brand == 42`
- **THEN** compilation fails with category `type mismatch`

### Requirement: Numeric `in` uses normalised values
Membership tests on numeric values SHALL compare normalised values, so an `int` property matches a
long literal.

#### Scenario: Int property in list
- **WHEN** `$arg0.year in [2023, 2024]` and `year` is an `int` of value 2024
- **THEN** it evaluates to `true`

### Requirement: Compile once, evaluate without parsing
Expressions SHALL be evaluated as a compiled tree; the hot path SHALL NOT parse strings, use
name-based reflection lookups, or guess types. `and`/`or` SHALL short-circuit.

#### Scenario: Short-circuit
- **WHEN** the left operand of `and` is `false`
- **THEN** the right operand is not evaluated

### Requirement: Null and error semantics
Evaluation SHALL treat `x == null` / `x != null` as reference tests, `size(null)` as `0`,
arithmetic with a null operand or integer division/modulo by zero as `null`, and any other
operation with a null operand as `false`. Runtime evaluation failures SHALL evaluate to `false`
and never reach the application.

#### Scenario: Evaluation error
- **WHEN** evaluation throws
- **THEN** the condition is `false` and the rule is skipped for that call

### Requirement: Invalid expression disables the rule
The agent SHALL disable the entire rule when its expression fails to parse or type-check
(`syntax error` — including an expression that is too long or nested too deep —, `type mismatch`, `unknown function`, `unknown property`, `erased collection type`,
`map unsupported`, `wrong arity`) and SHALL report the failure once with rule location and
category. The rest of the configuration SHALL remain active.

#### Scenario: Deeply nested expression
- **WHEN** a `<when>` is longer than 4 096 characters or nests deeper than 64 levels
- **THEN** it is a `syntax error`: the rule is disabled with one warning and no thread runs out of stack

#### Scenario: Typo in condition
- **WHEN** a rule's `<when>` references an unknown property
- **THEN** that rule enriches nothing and one warning names the rule and category `unknown property`

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

