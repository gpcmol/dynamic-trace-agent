## Why

Conditions are typed when a rule binds, from erased `Class` types. An element of a generic
collection therefore has no type, so `<when>$return[0].color == 'green'</when>` on
`List<Tree> getTree()` and `<when>$arg0.passengers[1].name == 'Jan'</when>` are rejected as
`erased collection type`, although the same paths work as attributes. Values whose static type is
`Object` can only be null-checked, so even where no type can ever be known (`List<?>`, raw `List`,
`Object getPayload()`) a simple comparison with a literal is impossible.

## What Changes

- **B — generic element types.** The condition typer reads the generic signature of the
  instrumented method, getters and fields (`List<Tree>` → `Tree`, `Tree[]`/`T[]`,
  `List<? extends Tree>` → `Tree`), resolving type variables through the receiver's class hierarchy
  (`class Forest extends Base<Tree>` with `List<T> getTree()` declared in `Base<T>`). A type
  variable that cannot be resolved stands for its bound (`T extends Tree` → `Tree`). Properties
  after a resolved element are checked at load as for any other type, so typos are reported.
- **C — dynamic operands.** A value whose static type is `Object` — an element of `List<?>`, of a
  raw `List`, of `List<T>` with unbounded `T`, an `Object` getter, `$argN` beyond the signature —
  takes its type from the other operand: the other side of a comparison or arithmetic, the
  literals of `in`, `String` for `like`/`ilike`, `Boolean` as a bare condition. At runtime the value
  is accepted only if it has that type (integral types for `Long`, `float`/`double` for `Double`);
  otherwise it counts as `null`, so the condition is `false`. Properties after an `Object` value are
  not checked at load (unchanged).
- `erased collection type` remains only for a property applied to a whole collection in a
  condition (`$arg0.passengers.name == 'Jan'`).
- Previously rejected conditions become valid; no valid condition changes meaning.
- Specs (`condition-dsl`), docs (`07-dsl.md`, README), `openspec/BACKLOG.md`, implementation and
  tests are aligned.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `condition-dsl`: static typing uses generic element types; `Object` operands are typed from
  their counterpart and checked at runtime; the root-index requirement no longer rejects generic
  lists.

## Impact

- `dta-core` `dsl`: `ExitSignature` (generic parameter/return types), `ConditionTyper` (walks
  `java.lang.reflect.Type`, dynamic operand coercion), `ValueNode` (coercing node).
- `dta-core` `path`: new `GenericTypes` helper, `PropertyAccessors.propertyGenericType`.
- `dta-core` `binding`: `RuleBinder` passes the generic signature. The attribute-path static check
  is unchanged (attributes already resolve elements at runtime).
- Hot path: typing is load-time only. Only conditions with dynamic operands evaluate one extra
  `instanceof` check per operand; statically typed conditions are unchanged.
- Tests in `dta-core` (`ConditionTest`, `RuleBinderTest`, `GenericTypesTest`), JMH benchmark for a
  dynamic comparison.
