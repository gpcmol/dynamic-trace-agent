# 08 — Performance

Goal (spec `hot-path-performance`): the best achievable per-call cost for enterprise production.
Overhead depends on the configuration, so the budget is expressed **per call**, not as one
percentage. Measured numbers: `benchmarks/` (see [11-benchmarking.md](11-benchmarking.md)).

---

## 1. Where time is spent

```
 application method returns
   │  advice (woven bytecode)                      ~0.5 ns   constant site id, no argument array
   ▼
 EnrichmentGateway.onExit                          volatile read of the handler
   ▼
 EnrichmentEngine.onExit
   ├─ volatile read of the published state         configuration disabled → return
   ├─ ClassValue → per-class entry → site lookup   no rule for this method → return      2.8 ns
   ├─ per-thread frame slot (+ re-entrancy guard)
   ├─ condition                                    false → return                          13 ns, 0 B
   └─ span: static Attributes (one call) + dynamic attributes        5 attributes → 174 ns
```

Measured on the reference host (JMH, `benchmarks/jmh-2026-10-09.md`):

| Path | ns/op | B/op |
|---|---:|---:|
| instrumented method without a rule | 2.8 | 0 |
| condition false (most frequent) | 13 | **0** |
| condition true, 0 / 5 / 20 dynamic attributes (+5 static) | 77 / 174 / 333 | 40 / 104 / 280 |
| read `$arg0.brand` | 3.7 | 0 |
| read `$arg0.passengers[1].name` | 7.1 | 0 |

End to end (k6, 1M requests, reference rule with 5 static + 12 dynamic attributes): **median 2.9 %**
throughput overhead versus the extension without configuration.

---

## 2. Hot-path rules (P1–P13)

| # | Rule | Where |
|---|---|---|
| P1 | advice only on selected `(class, method)` pairs, public concrete instance methods | `PlanMatchers` |
| P2 | no argument array for rules up to `$arg3` (fixed-arity advice) | `ExitAdvice.FixedArguments` |
| P3 | method identified by a constant site id, not by name | `SiteMappingFactory`, `ClassBindings` |
| P4 | per-class bindings on the class (`ClassValue`), no `(Class, String)` map lookups | `ClassBindings` |
| P5 | accessors discovered once per (class, property); never evicted | `PropertyAccessors` |
| P6 | typed `AttributeKey`s prepared per configuration | `EngineState`, `AttributeKeySet` |
| P7 | static attributes as one prebuilt `Attributes`, one `setAllAttributes` call | `SpanWriter` |
| P8 | boxing: getters return `Object` (see §3) | `PropertyAccessors` |
| P9 | literal `like` patterns compiled once | `LikePattern` |
| P10 | `in` lists as sorted primitive arrays / hash set | `MembershipConditions` |
| P11 | one thread-local lookup gives the frame and the re-entrancy guard | `ExitFrameSlot` |
| P12 | debug logging costs one final-field read when off | `AgentLog` |
| P13 | fallback span only when there is no current span | `MethodEnrichment` |

Cold path (C1–C5): name prefilter before hierarchy matching (C1), retransformation only for the plan
diff (C2/C3), file read only when time or size changed (C4), bounded memory (C5,
[09-memory-safety.md](09-memory-safety.md)).

---

## 3. Known costs and possible next steps

- **Boxing of numeric getters** when a condition compares a number that is reached (e.g.
  `$arg0.mileage gt 0` after the brand matched): 16–24 B per call. False conditions that
  short-circuit earlier allocate nothing. Option: primitive-typed readers for `long`/`int`/`double`
  leaves (`MethodHandle` of type `(Object)long`).
- **Primitive arguments** passed to the gateway are boxed (usually removed by escape analysis; spike
  S3: 16 B/op without).
- **Span attribute storage** dominates the condition-true path; it is the OTel SDK's cost.
- **Fallback spans** on high-volume methods called outside traces create many spans (behaviour kept
  on purpose; see `openspec/BACKLOG.md`).
