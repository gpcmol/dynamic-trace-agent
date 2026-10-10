## Context

The extension enriches existing OpenTelemetry spans with values read from selected method calls,
driven by an XML file that is reloaded while the JVM runs. The hard part is the classloader
boundary: advice is woven into application classes, while configuration and engine live in the
extension classloader. This design makes that boundary explicit and small, so the rest of the code
is ordinary Java. The target deployment, the OpenTelemetry Operator, allows exactly one
`-javaagent`.

Constraints:
- Runs as an OTel Java agent **extension only** (Operator `Instrumentation` CR, one `-javaagent`).
- Enterprise production: best achievable per-call performance, **no memory leaks** on
  instrumentation or reload, application behaviour never affected.
- Maintainable by a Java developer without AI: explicit boundaries, small packages, documented
  with ASCII diagrams (`docs/`).
- Maven, Java 21, OTel Java agent 2.30.x extension API.

Detailed explanations: `docs/02-architecture.md`, `docs/03-classloaders.md`.

## Goals / Non-Goals

**Goals:**
- Live, rule-based enrichment of existing spans as specified, including the decided behaviours.
- One explicit, documented classloader boundary (the gateway) instead of helper injection.
- Name-level configuration snapshot that needs no application classes → no startup timing hacks.
- Hot path: no lookups by name, no locks, no discovery, no allocation when a condition is false.
- Leak freedom proven by automated tests (classloader unload, reload soak).
- Single deployable artifact working under the OTel Operator.

**Non-Goals:**
- New span creation beyond the existing fallback span.
- Package/annotation/wildcard selectors, overload selection, Map traversal (see `BACKLOG.md`).
- JMX, web server, a second configuration format.
- OTel "indy" instrumentation mode as primary mechanism (experimental in OTel 2.x).

## Decisions

### D1 — Maven multi-module with a JDK-only core
Base package and groupId `org.otel.agent` (decided 2026-10-09).
Modules `dta-core` (JDK only), `dta-gateway` (JDK only, two types), `dta-extension`
(OTel/Byte Buddy, shaded), `dta-example-app`, `dta-benchmarks`.
*Why:* almost all logic (XML, DSL, paths, plans, binding, value conversion) becomes testable with
plain JUnit; dependency rules are enforceable.
*Alternatives:* single module (simpler build, but boundaries only by convention — rejected for
maintainability); separate `attach` module for a helper `-javaagent` (not deployable under the
Operator — rejected).

### D2 — Bootstrap gateway instead of helper injection
Advice calls only `EnrichmentGateway` (bootstrap, JDK types only), which forwards to an
`ExitHandler` registered by the engine in the extension classloader.
*Why:* zero helper-class lists, one engine and one snapshot per JVM, no reflection on the hot path.
*Alternatives:* inject the engine into every application loader (fragile, duplicated state);
serialise rules into system properties (per-call parsing); OTel indy mode
(right idea, experimental).

### D3 — Gateway installed by the extension itself, Instrumentation from OTel
`Instrumentation` is obtained from OTel's bootstrap `InstrumentationHolder` (internal API) through
one adapter class. The gateway classes are embedded in the extension jar as resources under
`dta-gateway/` (not on the package path, so the extension can never load its own copy) and defined in
bootstrap with Byte Buddy `ClassInjector.UsingUnsafe.ofBootLoader().injectRaw(…)` — no disk write.
The gateway package is registered through OTel's internal `BootstrapPackagesConfigurer` SPI so that
OSGi / JBoss Modules style classloaders delegate it to bootstrap.
*Validated by spike S1, S2, S6 (2026-10-09):* works with the Operator mechanism, in a read-only root
filesystem container, and for a classloader that only delegates `java.*`.
*Why:* the Operator deployment allows exactly one `-javaagent`.
*Alternatives:* helper `-javaagent` (impossible under the Operator); `appendToBootstrapClassLoaderSearch`
with a temp jar (needs a writable disk); no retransformation at all (breaks "remove the rule when the
incident is over").

### D4 — Two-level data model: name-level Snapshot, class-level bindings
`Snapshot` holds the plan (class/method names), compiled paths, untyped DSL ASTs and prebuilt static
attributes — no `Class` references. `ClassBinding` (typed condition, accessors, attribute keys) is
created lazily per runtime class and stored on that class via a single static `ClassValue` whose
per-class holder remembers the published configuration it was built for (by identity).
*Why:* config can be compiled before application classes exist (no deferred-load timer); leak
freedom by construction (references only point application → agent); per-class dispatch without
maps.
*Alternatives:* eager per-classloader compilation (needs class loading at the wrong
time, per-loader state); `Map<Class,…>` caches (pin classloaders); a new `ClassValue` per reload
(JDK `ClassValue` retention pitfall).

### D5 — Advice passes context without allocation in the common case
Two advice classes: `onExit(site, this, return, a0..a3)` for methods whose rules use at most
`$arg3`, `onExitAllArgs(…, Object[])` otherwise; chosen per method at transform time from the plan.
One advice serves void and value methods (`@Advice.Return(typing = DYNAMIC) Object` is `null` for
void — spike S7).
*Why (spike S3):* fixed arity is 0 B/op by construction; `@Advice.AllArguments` is 0 B/op only when
escape analysis succeeds and 24 B/op otherwise. Primitive arguments are boxed (accepted, documented).

### D6 — Per-method `site` constant
Each instrumented method gets a constant `site` id via Byte Buddy custom advice mapping
(`TypeTransformer.applyAdviceToMethod(matcher, customMapping, advice)`), resolved at transform
time from the current plan; ids are never reused within a JVM.
*Why (spike S4):* ~1 ns per call versus ~5 ns for `@Advice.Origin("#m")` + per-class lookup by name.
*Alternative:* lookup by name (rejected: 5× slower on the hot path).

### D7 — Publish first, then retransform
Reload publishes the new snapshot atomically, then retransforms only loaded classes affected by the
plan diff (computed by scanning loaded classes against old ∪ new plan; no "touched classes" list).
*Why:* correct in both directions (added rules ready before advice exists; removed rules no-op
before advice is stripped); nothing to leak.

### D8 — Validation in two phases
Document-level (syntax, shape, keys, paths, DSL syntax, protected namespaces) at load → reject
document / rule. Existence (class, method, property) and types → **rule silently ignored, exactly
one warning per rule and cause, whenever detected** (decided 2026-10-09). A selector class that
matches no loaded class gets one "not found or not loaded yet" warning after each load/reload; the
rule activates automatically if the class loads later.
*Why:* existence is only knowable when classes are loaded (and can differ per classloader).

### D9 — Per-thread reusable frame doubles as re-entrancy guard (revised during implementation)
The engine keeps one `ExitFrameSlot` per thread (`ThreadLocal`). Filling it replaces a per-call
frame allocation; its in-use flag is the re-entrancy guard (a nested selected method on the same
thread is skipped). All application references are cleared after every call.
*Why:* one thread-local lookup gives both an allocation-free frame and the guard; OTel's
`CallDepth` would add a second lookup and still require a frame allocation.
*Memory:* the slot's class belongs to the extension classloader (never unloaded) and it holds no
application object after a call, so it cannot pin an application classloader.

### D10 — Precomputed telemetry artefacts
Per snapshot: static attributes as one OTel `Attributes`; per ClassBinding: typed `AttributeKey`s
and accessor chains; literal `like` patterns compiled; large `in` lists as hash sets.
*Why:* no per-call `computeIfAbsent`, key allocation or pattern interpretation.

### D11 — Accessors as plain `MethodHandle`s attached to the application class
Getter/field accessors are `MethodHandle`s (adapted to `(Object)Object`) held in the class binding,
which lives in the engine's static `ClassValue` entry of the application class.
*Why (spike S5):* the application classloader is collected with this layout. `LambdaMetafactory` is
not possible for application classes: with the extension's lookup the generated class cannot see
the application class; with `privateLookupIn(appClass)` LMF rejects the caller.
*Alternative:* generating accessor classes into the application classloader (rejected: complex,
new leak surface for a few ns).

### D12 — Single poller thread, started at agent start
Started from `AgentListener.afterAgent`, daemon, context classloader `null`; compares mtime/size
before reading; unreadable file → keep config, warn once per episode; missing file → disable.

### D13 — Quality gates
Spotless (google-java-format), Error Prone, ArchUnit (no cycles, module/layer rules, advice may
only reference JDK + gateway, no `Map<Class,…>` fields in long-lived types), Maven enforcer.

## Risks / Trade-offs

- [Two internal OTel APIs are used: `InstrumentationHolder` and `BootstrapPackagesConfigurer`] →
  each behind one adapter class; CI matrix over supported agent versions; degraded mode (no-op
  advice, no retransformation) if `Instrumentation` is unavailable; recommend pinned agent image.
- [`ClassInjector.UsingUnsafe` unavailable on some JVM] → extension disables itself with one warning
  (application unaffected).
- [OSGi / JBoss Modules] → supported through boot delegation (S6), but not yet tested on a real
  OSGi or JBoss server; add to the integration matrix when such a target is needed.
- [Lazy binding moves some config errors from load time to first use] → one clear warning per rule
  and class; debug log lists bound/failed rules; documented in config docs.
- [Two advice variants] → two small advice classes (S3 justified fixed arity; S7 removed the
  void/value split).
- [Primitive arguments are boxed when passed to the gateway] → only for arguments the advice passes;
  usually removed by escape analysis; 16 B/op worst case per primitive outside the Integer cache.
- [`MethodHandle` accessor slightly slower than generated code] → measured in task 14.1.
- [Scanning all loaded classes on reload is O(classes)] → cold path, measured in benchmarks;
  prefilter by class names.
- [Fallback span on high-volume methods outside traces] → behaviour kept on purpose; documented;
  backlog item for a switch.

## Deployment Plan

- Build the extension image (`Dockerfile`) and reference it in the `Instrumentation` CR
  (`java.extensions`); pin the agent image to a version from the CI matrix.
- Mount the rule ConfigMap as a directory and set `DYNAMIC_TRACE_CONFIG_FILE`
  (`deploy/kubernetes/`).
- Rollback: the previous extension image tag; removing the extension image disables everything.
- The reference configuration `config/agent-config.xml` is the example, test fixture and benchmark
  configuration at the same time.

## Open Questions

None. Spike outcomes are recorded in `docs/03-classloaders.md` §11 and `benchmarks/spike-2026-10-09.md`.
