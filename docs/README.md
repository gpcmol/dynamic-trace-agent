# Documentation

Read in this order. Normative requirements are in `openspec/specs/`; these documents explain the
design behind them.

| Document | Content |
|---|---|
| [00-overview.md](00-overview.md) | Problem, what it is, audience, scope, guarantees |
| [01-glossary.md](01-glossary.md) | Terms and the classes that implement them |
| [02-architecture.md](02-architecture.md) | Context, Operator deployment, modules, packages, data pipeline, threads |
| [03-classloaders.md](03-classloaders.md) | The classloader boundary, the gateway, lifecycles, leak rules, spike results |
| [04-flows.md](04-flows.md) | Start-up, configuration change, method exit, retransformation |
| [05-state-machines.md](05-state-machines.md) | Configuration lifecycle (inactive, disabled, active; invalid, unreadable, missing) |
| [06-config-xml.md](06-config-xml.md) | XML reference, paths, validation messages, migration |
| [07-dsl.md](07-dsl.md) | Condition language: grammar, typing, null semantics, failures |
| [08-performance.md](08-performance.md) | Hot-path rules, measured costs, next steps |
| [09-memory-safety.md](09-memory-safety.md) | Leak catalogue, rules, tests |
| [10-code-guidelines.md](10-code-guidelines.md) | Build gates, where code goes, style, tests, upgrades |
| [11-benchmarking.md](11-benchmarking.md) | Running JMH and k6 benchmarks, reading results |
