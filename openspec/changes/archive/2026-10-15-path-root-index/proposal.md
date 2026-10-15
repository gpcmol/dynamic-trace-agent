## Why

A path can only index a collection that is reached through a property (`$arg0.passengers[1].name`).
When the method itself returns a `List`, array or other `Iterable` — or takes one as argument —
there is no property to index, so "the color of the first tree of `Forest.getTree()`" cannot be
expressed: `$return[0].color` is rejected and `$return.color` yields the colors of *all* trees.

## What Changes

- The path root (`$this`, `$return`, `$argN`) MAY be followed by one `[index]`:
  `$return[0].color`, `$arg1[2].name`, `$this[0].id`. The index selects one element of the root
  value (array, `List`, other `Iterable`) with the same rules as an indexed segment: out of range,
  `null` or a non-collection root gives `null`, a non-`Collection` `Iterable` is walked for at most
  1 000 elements.
- An attribute path may consist of an indexed root only (`$return[0]`): the selected element
  itself is the value. A bare root without index (`$return`) stays invalid as attribute path.
- Conditions accept an indexed root as reference (`$return[0].color == 'green'`), typed the same way
  as an indexed segment: the component type of an array; a generic `List`/`Iterable` element is an
  `erased collection type` error, exactly like `$arg0.passengers[1].name` today.
- The static attribute-path check at bind time uses the array component type after a root index
  and stops for a `List`/`Iterable` (resolved at runtime), as for indexed segments.
- Invalid forms are rejected at load: `$return[]`, `$return[01]`, `$return[-1]`, `$return[0][1]`,
  `$return[0]x`, `$return [0]`.
- Specs (`value-path-resolution`, `condition-dsl`), docs (`06-config-xml.md`, `07-dsl.md`,
  `01-glossary.md`), Javadoc grammar, implementation and tests are brought in line with each other.

No existing valid path changes meaning; this only accepts paths that are rejected today.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `value-path-resolution`: path grammar gains an optional root index; root indexing semantics;
  an attribute path may be an indexed root; static property check after a root index.
- `condition-dsl`: references may use an indexed root; its static type follows the indexing rules.

## Impact

- `dta-core` `path`: `PathCompiler` (grammar, root index parsing), `CompiledPath` (root index,
  `toString`), `PathReader` (select on the root; shared `select` helper).
- `dta-core` `dsl`: `ConditionTyper.staticType` (root index typing). `Lexer` already reads `[`/`]`
  as path characters — verify only.
- `dta-core` `binding`: `RuleBinder.checkAttributePath` (root index before the property loop).
- Tests: `PathCompilerTest`, `PathReaderTest`, condition typing/parsing tests, `RuleBinder` tests;
  hot-path behaviour unchanged for paths without a root index.
- Docs: `docs/06-config-xml.md` §4, `docs/07-dsl.md` grammar and typing table,
  `docs/01-glossary.md` path entry.
- No configuration format, packaging or classloader impact. No breaking change.
