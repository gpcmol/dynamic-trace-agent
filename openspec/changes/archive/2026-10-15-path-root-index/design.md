## Context

Paths are compiled by `PathCompiler` into a `CompiledPath(root, segments)` and read by
`PathReader`. An index only exists on a `PathSegment` (`property[index]`), so a collection that *is*
the root value (a returned `List<Tree>`, an array argument) cannot be indexed. Three consumers walk
the compiled path and must learn the root index:

- `PathReader` — runtime value (hot path, monomorphic inline cache per segment);
- `RuleBinder.checkAttributePath` — static property check of attribute paths at bind time;
- `ConditionTyper.staticType` — static typing of condition references.

The condition `Lexer` already treats `[` and `]` as path characters, so `$return[0].color` reaches
`PathCompiler.reference` as one token.

## Goals / Non-Goals

**Goals:**

- `$this[i]`, `$return[i]`, `$argN[i]` with the same semantics as an indexed segment.
- No extra cost on the hot path for paths without a root index.
- Spec, docs, Javadoc grammar, implementation and tests describe the same grammar.

**Non-Goals:**

- Multiple indexes (`$return[0][1]`, `items[0][1]`): a segment has one index today; this change
  keeps that rule for the root.
- Generic element types for conditions (`List<Tree>` → `Tree`). Conditions on a generic list root
  stay an `erased collection type` error, consistent with `$arg0.passengers[1].name`.
- Negative or "last element" indexes, slices, map keys.

## Decisions

1. **Model: an optional root index on `CompiledPath`, not a property-less segment.**
   `CompiledPath(PathRoot root, int rootIndex, List<PathSegment> segments)` with
   `PathSegment.NO_INDEX` as "none", plus `hasRootIndex()`. A convenience constructor
   `CompiledPath(root, segments)` keeps existing call sites and tests unchanged.
   *Alternative:* a `PathSegment` with an empty property. Rejected: every consumer loops over
   segments calling `PropertyAccessors` with `segment.property()`; an empty property would need a
   special case in each loop and would weaken the "a segment is an identifier" invariant.
   *Alternative:* index on `PathRoot`. Rejected: `PathRoot` is a sealed set of root kinds that
   `ExitFrame` selects from; mixing an index in it spreads indexing into frame selection.

2. **Parsing.** In `PathCompiler.compile`, `parts[0]` (before the first `.`) is split at the first
   `[`: the part before is parsed by the existing `parseRoot`, the bracket part by the same index
   rules as segments (canonical digits, at most 9 digits, exactly one `[...]` at the end). The index
   parsing moves to one private helper used by both root and segment, so the error messages are
   identical. `attributePath` requires `!segments.isEmpty() || hasRootIndex()`; the message
   becomes "a property or index is required after $return".

3. **Reading.** `PathReader.readFrom(root)` applies the root index first via the existing
   `select(value, index)` (moved from `SegmentReader` to a static helper of `PathReader`), then
   continues as today. A `Map` root yields `null` because `select` only handles arrays and
   `Iterable`s. The root index is a final `int` field; a path without one takes one extra
   comparison, no allocation.

4. **Static attribute check (`RuleBinder`).** After `rootType`, a root index maps the type with
   the existing `componentTypeOrNull`: array → component type, otherwise `null` (stop, resolve at
   runtime). Same rule as an indexed segment.

5. **Condition typing (`ConditionTyper`).** After `rootType`, a root index maps the type with the
   existing `elementType(path, …)`: array → component type, `Iterable` → `erased collection type`,
   else `type mismatch`. `elementType` takes a description of what is indexed instead of a
   `PathSegment` so it serves root and segment.

6. **`toString`.** `CompiledPath.toString()` renders `$return[0].color`, so warnings quote the path
   as written.

7. **Limits.** The root index does not count as a segment for the 32-segment limit (it adds no
   getter call) and uses the existing 0..999 999 999 index range and 1 000-element iteration bound.

## Risks / Trade-offs

- [Users expect `$return[0].color` in a `<when>` on a `List<Tree>` to work] → documented as
  `erased collection type` with the same workaround as today (index an array, or use
  `size()`/`contains()`); generic typing is listed as a possible follow-up.
- [Hot path regression] → the extra branch is on a final field; covered by existing JMH
  benchmarks (`dta-benchmarks`), no new allocation.
- [Error message drift between root and segment index] → one shared parsing helper and tests that
  assert both messages.

## Migration Plan

None: only paths that are rejected today become valid. Rollback is reverting the change; a rule
file using a root index is then rejected as before (last valid configuration stays active).

## Open Questions

- Should conditions read the generic return/parameter type so `$return[0].color` on `List<Tree>`
  type-checks (bigger change, also affects `passengers[1].name`)?

Decided:

- `$this[i]` is supported: the same rule for every root.
- No end-to-end test; `dta-core` unit tests cover compiler, reader, binder and conditions. The
  example app stays unchanged.
