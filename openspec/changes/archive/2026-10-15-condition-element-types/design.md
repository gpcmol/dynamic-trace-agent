## Context

`ConditionTyper.staticType` walks a `CompiledPath` with `Class` values taken from
`ExitSignature` (raw parameter/return types) and `PropertyAccessors.propertyType` (raw getter/field
types). An index on an `Iterable` cannot know its element type and throws `erased collection type`.
`Object` ends the walk and is then only usable for `== null`, `size` and `contains`.

## Goals / Non-Goals

**Goals:**

- B: typed conditions on elements of generic collections wherever the type is in the code.
- C: usable conditions where the type is genuinely unknown, with runtime type checks.
- No cost for conditions that are fully statically typed; zero allocation stays zero.

**Non-Goals:**

- Generic checks for attribute paths: attributes already resolve elements at runtime; making the
  bind-time check stricter would disable rules that work today.
- Comparing a property applied to a whole collection (`passengers.name == 'Jan'`): that is an
  "any element" semantic, see backlog.
- Enum ↔ string comparison, mixed `Long`/`Double` comparison.

## Decisions

1. **Walk `java.lang.reflect.Type`, decide on its erasure.** `staticType` keeps a `Type`; every
   check (Map, array, Iterable, property lookup) uses `GenericTypes.erase(type)`. The value node
   still carries the normalised `Class`. This keeps all existing rules and messages.
2. **`GenericTypes` (new, `path` package next to `PropertyAccessors`, JDK only):**
   - `erase(Type)`: `Class` → itself, `ParameterizedType` → raw, `GenericArrayType` → array class
     of the erased component, `WildcardType` → erasure of the upper bound, `TypeVariable` →
     erasure of its first bound.
   - `elementType(Type)`: array → (generic) component; `Iterable` → the resolved type argument of
     `Iterable<T>` as seen from the type (walking generic superclasses/interfaces with
     substitution); not resolvable → its bound, which is `Object` for `List<?>`, raw `List`,
     unbounded `T`.
   - `resolve(Type member, Type context)`: substitutes type variables of the member's declaring
     class with the arguments of `context`'s matching supertype. Method type variables stay
     variables and therefore fall back to their bound.
   - Small private `ParameterizedType`/`GenericArrayType` records for substituted types.
   *Alternative:* a library (Guava `TypeToken`, gentyref). Rejected: `dta-core` is JDK only and
   ships inside the agent.
3. **`ExitSignature` gains generic types.** New components `genericParameterTypes` and
   `genericReturnType`; the existing three-argument constructor fills them with the raw classes,
   so callers and tests keep working. `RuleBinder` passes `method.getGenericParameterTypes()` and
   `getGenericReturnType()` (raw fallback when lengths differ). Root types are resolved against the
   receiver (`$this`), so `List<T>` declared in `Base<T>` becomes `List<Tree>` on `Forest`.
4. **`PropertyAccessors.propertyGenericType(Class, String)`** returns the generic return type of
   the same getter/record accessor/field that `propertyType` finds. The typer resolves it against
   the current `Type` (so `Box<Tree>.content` with `T getContent()` gives `Tree`).
5. **Dynamic operands = static type `Object`.** In `comparison`, `membership`, `like`, `truthy` and
   `arithmetic`, an `Object` operand facing a `String`/`Long`/`Double`/`Boolean` (or the `in`
   candidates' type, `String`, `Boolean`) is wrapped in `ValueNode.Coerced(node, type)`. Its
   `value()` returns the value if it is an instance of the accepted classes, otherwise `null`. The
   rest of the typer then sees equal types and builds the same `Condition` records as today.
   `Object` vs `Object` keeps today's `Equality`. Arithmetic with two dynamic sides stays a
   `type mismatch` (no type to choose).
   *Alternative:* a separate dynamic condition family. Rejected: duplicates every operator.
6. **`erased collection type`** stays only for a property applied to a collection
   (`collectionNotSupported`); an index never throws it anymore.

## Risks / Trade-offs

- [Dynamic operands hide typos: `$return[0].colr == 'x'` on `List<?>` is always `false`] → B makes
  this the exception (only where the type is truly unknown); docs say so explicitly.
- [Conditions that were rejected now load] → only conditions that previously failed to type-check;
  documented as a relaxation, no valid condition changes meaning.
- [Generic resolution bugs give a wrong static type → valid rule rejected] → resolution falls back
  to the bound (never to a narrower type); covered by `GenericTypesTest` with superclass,
  interface, nested, wildcard, array and raw cases.
- [Hot path] → `Coerced` only exists in dynamic conditions; JMH adds `evaluateDynamicCondition`
  and the existing benchmarks must stay within error bars.

## Migration Plan

None. Rollback reverts to rejecting these conditions (rules disabled with a warning, last valid
configuration semantics unchanged).

## Open Questions

None.
