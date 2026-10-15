## MODIFIED Requirements

### Requirement: Static typing
Expressions SHALL be type-checked once at load against the selected class and the selected
method's parameter/return types. Integral types normalise to long and floating types to double.
`==`/`!=` require equal normalised types (or a `null` literal); `lt`/`lte`/`gt`/`gte` require
numeric operands; `like`/`ilike` require strings; `contains`/`icontains` take a String or a
Collection haystack and a String needle; `in` elements must match the value's normalised type.

Types SHALL be taken from the generic signatures of the instrumented method, getters, record
components and fields. The element type of an indexed array or `Iterable` SHALL be its generic
element type; a type variable SHALL be resolved through the receiver's class hierarchy and through
the type arguments of the value it is read from, and otherwise SHALL stand for its first bound; a
wildcard SHALL stand for its upper bound.

A value whose static type is `Object` (dynamic) SHALL take its type from its counterpart: the other
operand of a comparison or arithmetic operation when that is a `String`, `Long`, `Double` or
`Boolean`; the candidates of `in`; `String` for `like`/`ilike`; `Boolean` when used as a condition.
At runtime a dynamic value SHALL be used only if it is of that type (`Long`: byte, short, int, long;
`Double`: float, double); any other value SHALL count as `null`. Two dynamic operands SHALL only be
compared with `==`/`!=` (equality of the values).

#### Scenario: Type mismatch
- **WHEN** the expression is `$arg0.brand == 42`
- **THEN** compilation fails with category `type mismatch`

#### Scenario: Generic list element
- **WHEN** the method returns `List<Tree>` and the expression is `$return[0].color == 'green'`
- **THEN** it compiles with `color` checked on `Tree`, and is `true` when the first tree is green

#### Scenario: Typo after a generic element
- **WHEN** `passengers` is a `List<Passenger>` and the expression is `$arg0.passengers[1].nmae == 'Jan'`
- **THEN** compilation fails with category `unknown property`

#### Scenario: Type variable resolved through the class hierarchy
- **WHEN** `Forest extends Base<Tree>`, `Base<T>` declares `List<T> getTree()` and the expression
  is `$return[0].color == 'green'` on `Forest`
- **THEN** `color` is checked on `Tree`

#### Scenario: Dynamic element
- **WHEN** the method returns `List<?>` and the expression is `$return[0].color == 'green'`
- **THEN** it compiles; it is `true` when the first element has a `color` equal to `green`, and
  `false` when the element has no `color` or its `color` is not a `String`

#### Scenario: Dynamic number
- **WHEN** `getPayload()` returns `Object` and the expression is `$arg0.payload gt 10`
- **THEN** it is `true` for an `Integer` 11 and `false` for a `Double` 11.5 or a `String`

#### Scenario: Dynamic boolean condition
- **WHEN** the method returns a raw `List` and the expression is `$return[0].active`
- **THEN** it is `true` only when the first element's `active` is `Boolean.TRUE`

### Requirement: Indexed root references
A reference in a condition SHALL accept a root index (`$return[0].color`, `$arg0[1]`). Its static
type SHALL follow the same rules as an indexed segment: the generic element type of an array or
`Iterable` root (dynamic when it cannot be known); any other root type SHALL fail with category
`type mismatch`. At runtime the root index SHALL select the element as specified in
value-path-resolution.

#### Scenario: Array root in a condition
- **WHEN** the method returns `Tree[]` and the condition is `$return[0].color == 'green'`
- **THEN** it compiles and is `true` when the first tree is green

#### Scenario: Generic list root in a condition
- **WHEN** the method returns `List<Tree>` and the condition is `$return[0].color == 'green'`
- **THEN** it compiles with `color` checked on `Tree`

#### Scenario: Index on a non-collection root
- **WHEN** the first parameter is a `Car` and the condition is `$arg0[0].brand == 'bmw'`
- **THEN** compilation fails with category `type mismatch`

#### Scenario: Out-of-range root index
- **WHEN** the method returns `Tree[]` of length 1 and the condition is `$return[1] == null`
- **THEN** it evaluates to `true`

### Requirement: Invalid expression disables the rule
The agent SHALL disable the entire rule when its expression fails to parse or type-check
(`syntax error` — including an expression that is too long or nested too deep —, `type mismatch`,
`unknown function`, `unknown property`, `erased collection type` — a property applied to a whole
collection —, `map unsupported`, `wrong arity`) and SHALL report the failure once with rule
location and category. The rest of the configuration SHALL remain active.

#### Scenario: Deeply nested expression
- **WHEN** a `<when>` is longer than 4 096 characters or nests deeper than 64 levels
- **THEN** it is a `syntax error`: the rule is disabled with one warning and no thread runs out of stack

#### Scenario: Typo in condition
- **WHEN** a rule's `<when>` references an unknown property
- **THEN** that rule enriches nothing and one warning names the rule and category `unknown property`

#### Scenario: Property on a whole collection
- **WHEN** the condition is `$arg0.passengers.name == 'Jan'`
- **THEN** compilation fails with category `erased collection type`

### Requirement: Compile once, evaluate without parsing
Expressions SHALL be evaluated as a compiled tree; the hot path SHALL NOT parse strings, use
name-based reflection lookups, or guess types. `and`/`or` SHALL short-circuit. A dynamic operand
SHALL cost at most one type test per evaluation; statically typed operands SHALL NOT pay for it.

#### Scenario: Short-circuit
- **WHEN** the left operand of `and` is `false`
- **THEN** the right operand is not evaluated

#### Scenario: Static operands are not coerced
- **WHEN** the condition is `$arg0.brand == 'bmw'` and `brand` is a `String` getter
- **THEN** the compiled condition contains no runtime type coercion
