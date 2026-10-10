# 09 — Memory safety

Requirement (spec `memory-safety`): **no memory leaks while instrumenting or reloading the
configuration** — the agent runs for months in enterprise JVMs, often in application servers that
undeploy and redeploy web applications.

---

## 1. The one rule

```
  application Class ──(ClassValue entry)──► agent data         ✔ allowed
  agent (gateway, engine, poller, snapshot, caches) ──────────► application Class  ✘ forbidden
```

An application classloader is collectable when nothing outside it references its classes. The
agent lives forever (bootstrap and extension classloaders are never unloaded), so **every reference
from agent structures to application classes or objects is a potential leak**. Per-class data is
therefore attached *to the class* (via `ClassValue`), and agent-wide data contains names only.

---

## 2. Leak catalogue

| # | Source | Rule | Implementation | Test |
|---|---|---|---|---|
| L1 | `Class` refs in long-lived structures | config, plan, snapshot and parsed expressions hold **names only** | `TransformationPlan`, `Snapshot`, `Expression` | ArchUnit `snapshotDataHoldsNoClassReferences` |
| L2 | `ClassValue` per reload (value → ClassValue → retained) | **one** static `ClassValue` per purpose for the JVM's lifetime | `ClassBindings.PER_CLASS`, `PropertyAccessors.READERS` | `ClassLoaderUnloadTest` |
| L3 | generated accessor classes, evict-and-rediscover churn | plain `MethodHandle`s (no generated classes; spike S5); discovered once per (class, property), never evicted | `PropertyAccessors` | `ClassLoaderUnloadTest`, `ReloadSoakTest` (class count) |
| L4 | `AttributeKey` caches growing over reloads | keys belong to the published configuration and are replaced with it | `EngineState.keys` | `ReloadSoakTest` (heap) |
| L5 | poller thread inheriting an application context classloader | exactly one daemon thread, context classloader `null` | `ExtensionRuntime.newPollerThread` | `PollerThreadTest` |
| L6 | `ThreadLocal` values on pooled application threads | only an agent type (extension classloader); all application references cleared after each call | `ExitFrameSlot.release` | `ClassLoaderUnloadTest` (same thread reused) |
| L7 | application objects kept after a call | receiver, arguments, return and values live only during one call; never logged | `EnrichmentEngine`, `AgentLog` | review + `ClassLoaderUnloadTest` |
| L8 | snapshot chains (new → old → older) | a snapshot never references its predecessor; the diff is computed and dropped | `ReloadCoordinator.activate` | `ReloadSoakTest` |
| L9 | "transformed classes" bookkeeping | none kept: retransformation scans loaded classes against old ∪ new plan | `Retransformer` | — (nothing to leak) |
| L10 | "warn once" bookkeeping | reset when a configuration is published → bounded by one configuration | `AgentLog.resetOnce` | `ReloadSoakTest` |
| L11 | configuration text per poll | one current copy; unchanged time/size → not read | `ConfigFileWatcher` | `ConfigFileWatcherTest` |
| L12 | path-reader inline cache pointing at a *child* classloader's class | cache only classes of the owner's classloader or its parents | `PathReader.SegmentReader` | review (documented invariant) |

Stale per-class entries: a class that is not called again after a reload keeps its last binding
until it is called again or unloaded — at most **one** stale entry per class, never one per reload.

---

## 3. How the tests prove it

| Test | What it does | Fails when |
|---|---|---|
| `ClassLoaderUnloadTest` | loads a class in its own classloader, enriches it under five successive configurations on the same thread, drops all references, runs GC | the classloader is still reachable (verified to fail with one planted static reference) |
| `ReloadSoakTest` | 10 000 reloads with alternating conditions, attributes and keys, with calls in between | heap after GC grows > 8 MB, > 50 classes are loaded, or threads grow |
| `PollerThreadTest` | starts the runtime from a thread whose context classloader is an application loader | more than one poller, not a daemon, or a non-null context classloader |
| ArchUnit (`ArchitectureTest`) | static rules: no `Map<Class,…>` fields; no `Class`/`ClassLoader` fields in config/plan/snapshot/expressions | a change introduces such a field |

The benchmark suite adds a long-running soak under load with periodic reloads and reports heap and
metaspace over time (`docs/11-benchmarking.md`).

---

## 4. Rules for maintainers

- Never add a field of type `Class`, `ClassLoader`, `Method`, `MethodHandle` or an application
  object to anything reachable from the engine, the gateway, the poller or a snapshot. Put
  per-class data in `ClassBindings` (its `ClassValue` entry).
- Never create a `ClassValue` per configuration or per call.
- Never store a value you read from the application (not even for a debug message).
- New `ThreadLocal`s: agent types only, cleared after use.
- New threads: daemon, context classloader `null`, started by the agent, not on behalf of an
  application thread.
- When in doubt, extend `ClassLoaderUnloadTest` with the new structure.
