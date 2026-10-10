## ADDED Requirements

### Requirement: Application classloaders stay collectable
The agent SHALL NOT keep any strong reference, directly or transitively, from a long-lived
structure (gateway, extension singletons, poller, caches, threads) to an application `Class`,
`ClassLoader`, accessor or `MethodHandle` of that loader. Per-class data SHALL be attached to the
class itself (e.g. `ClassValue`) or held weakly.

#### Scenario: Web application undeployed
- **WHEN** an instrumented application classloader is discarded after enrichment and several reloads
- **THEN** after GC the classloader is collected (verified by a test with a `WeakReference` to it)

### Requirement: Reload does not accumulate memory
Each reload SHALL replace the previous snapshot completely; no snapshot SHALL reference its
predecessor, and per-snapshot data (bound rules, accessors, attribute keys, compiled conditions)
SHALL become unreachable once the snapshot is replaced. Caches SHALL be bounded by the size of the
active configuration, not by the number of reloads or the number of distinct runtime values.

#### Scenario: Reload soak
- **WHEN** the configuration is reloaded 10,000 times (alternating rule sets) under load
- **THEN** heap after full GC, metaspace, loaded-class count and thread count return to a stable
  plateau (no monotonic growth)

### Requirement: No per-reload ClassValue or hidden-class churn
The agent SHALL NOT create a new `ClassValue` instance per reload whose values can strongly
reference the `ClassValue` or the snapshot, and SHALL NOT generate accessor classes
(`LambdaMetafactory`/hidden classes) repeatedly for the same member on the hot path (no
evict-and-rediscover).

#### Scenario: Many distinct runtime classes
- **WHEN** a selector matches thousands of subclasses
- **THEN** accessor generation happens at most once per (class, member) per active snapshot and is
  released with that snapshot or class

### Requirement: Threads do not pin classloaders
The agent SHALL run exactly one configuration poller thread, created as a daemon with context
classloader `null` and no inherited application context, regardless of how many application
classloaders exist. The poller SHALL NOT be started from, or capture, an application classloader.

#### Scenario: Poller created during application class loading
- **WHEN** the first trigger to start the poller happens on an application thread
- **THEN** the poller thread's context classloader is `null` and it holds no reference to that loader

### Requirement: No retention of application objects
Receivers, arguments, return values and resolved values SHALL only be referenced for the duration
of one enrichment call; they SHALL NOT be stored in fields, caches, `ThreadLocal`s, log state or
exceptions kept by the agent. `ThreadLocal` values SHALL be JDK types or agent types loaded by the
extension classloader, and SHALL hold no application object after the call returns.

#### Scenario: Pooled worker threads
- **WHEN** enrichment runs on a long-lived thread pool
- **THEN** no agent `ThreadLocal` value references an application object or an application class

### Requirement: Retransformation bookkeeping is weak and bounded
Tracking of transformed classes per loader SHALL use weak references and SHALL drop entries for
classes that are unloaded or restored to original bytecode.

#### Scenario: Rule removed
- **WHEN** a rule is removed and its classes are restored
- **THEN** the bookkeeping for those classes is removed

### Requirement: Leak tests are part of the build
The build SHALL include automated leak tests: classloader unload after enrichment and reloads,
reload soak with heap/metaspace/class-count plateau checks, and a long-running macro soak in the
benchmark suite (load plus periodic reloads) reporting heap-after-GC and metaspace over time.

#### Scenario: Leak regression
- **WHEN** a change introduces a strong reference from the gateway to an application class
- **THEN** the classloader-unload test fails
