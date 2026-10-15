## Context

`MethodEnrichment.RuleRunner` evaluates a rule's condition with `Condition.evaluate` (failures count
as `false`) and writes each attribute with `writer.write(span, key, convert(reader.read(frame)))`;
`null` is skipped. Nothing is logged.

## Decisions

1. **Debug path in a subclass.** In debug mode `MethodEnrichment.of` builds an
   `ExplainedRuleRunner` (with a `RuleDiagnostics`) instead of a `RuleRunner`. The subclass is never
   loaded with debug off, so the normal path is the previous code and stays monomorphic. A first
   version with a `null` check per rule and attribute made `conditionTrue` bimodal under the JIT
   (see `benchmarks/jmh-2026-10-15.md`).
2. **Once per (rule, attribute, binding)** with plain `boolean[]` flags: no map lookups, no key
   strings on the hot path; a race reports twice at worst. A new configuration rebinds and so
   reports again.
3. **Debug path splits read and convert** to tell "no value" from "cannot be written"; the getter
   failure itself stays inside `PathReader` (it returns `null`), so the message names the possible
   causes instead of guessing one.
4. **Values are never logged**, only keys, paths, rule identity, `Class#method` and the value's
   type name.

## Risks / Trade-offs

- [A throwing getter cannot be told apart from a `null`] → the message lists both; distinguishing
  them would need a second result channel in `PathReader` on the hot path.
