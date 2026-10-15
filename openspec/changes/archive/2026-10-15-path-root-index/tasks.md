## 1. Compiled model and parsing

- [x] 1.1 Add `rootIndex` (`PathSegment.NO_INDEX` = none) and `hasRootIndex()` to `CompiledPath`, keep a `CompiledPath(root, segments)` constructor, render the root index in `toString()`
- [x] 1.2 Extract index parsing in `PathCompiler` into one helper used by root and segment (canonical digits, ≤ 9 digits, exactly one `[...]` at the end, same messages)
- [x] 1.3 Parse an optional `[index]` after `$this` / `$return` / `$argN`; reject `$return[]`, `[01]`, `[-1]`, `[0][1]`, `[0]x`, `$return[0].` and an over-long index
- [x] 1.4 `attributePath` accepts a root index without segments; message "a property or index is required after …"
- [x] 1.5 Update the grammar in the `PathCompiler` and `CompiledPath` Javadoc

## 2. Runtime resolution

- [x] 2.1 Move `select(value, index)` from `SegmentReader` to a static helper in `PathReader`
- [x] 2.2 Apply the root index in `PathReader.readFrom` before the segments; indexed root without segments returns the element
- [x] 2.3 Update the semantics list in the `PathReader` Javadoc

## 3. Static checks

- [x] 3.1 `RuleBinder.checkAttributePath`: after `rootType`, map a root index with `componentTypeOrNull` (array → component, else stop)
- [x] 3.2 `ConditionTyper.staticType`: after `rootType`, map a root index via `elementType` (array → component, `Iterable` → `erased collection type`, else `type mismatch`); generalise `elementType`'s description argument
- [x] 3.3 Verify `Lexer` passes `$return[0].color` as one path token (test only, no code change expected)

## 4. Tests

- [x] 4.1 `PathCompilerTest`: valid root-index paths for each root, `toString` round trip, invalid forms from the spec, attribute path `$return[0]` accepted and `$return` rejected
- [x] 4.2 `PathReaderTest`: `$return[0].color` on `List`, array, non-`Collection` `Iterable`, endless `Iterable`; out of range, empty, `null`, `Map` and non-collection root → `null`; `$return[0]` alone; root index followed by flattening (`$return[0].passengers.name`)
- [x] 4.3 `RuleBinderTest`: array root of a final type with missing property → rule ignored with warning; `List` root → stays active
- [x] 4.4 `ConditionTest` / `ExpressionParserTest` / `LexerTest`: array root compiles and evaluates; `List` root → `erased collection type`; non-collection root → `type mismatch`; `$return[1] == null` out of range
- [x] 4.5 `XmlConfigurationParserTest` / `SnapshotCompilerTest`: a rule with `path="$arg2[0].name"` loads and counts `$arg2` for the argument mode

## 5. Documentation and specs

- [x] 5.1 `docs/06-config-xml.md` §4: grammar with root index, examples `$return[0].color` and `$return[0]`, limits note
- [x] 5.2 `docs/07-dsl.md`: `PATH` grammar, typing table row for a root index, `erased collection type` example
- [x] 5.3 `docs/01-glossary.md`: path entry mentions the root index
- [x] 5.4 Check README and `docs/00-overview.md` for path grammar statements that contradict the new rule

## 6. Verification

- [x] 6.1 `mvn verify` passes (format, Error Prone, unit, architecture, memory and end-to-end tests)
- [x] 6.2 `openspec validate path-root-index --strict` passes
- [x] 6.3 Cross-check: every scenario in the delta specs has a test, and docs, Javadoc and spec show the same grammar
