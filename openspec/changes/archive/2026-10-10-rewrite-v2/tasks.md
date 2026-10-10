## 1. Project setup

- [x] 1.1 Create Maven parent pom (`org.otel.agent`, Java 21, pinned OTel agent 2.30.x / extension API / Byte Buddy versions as `provided`) and empty modules `dta-core`, `dta-gateway`, `dta-extension`, `dta-example-app`, `dta-benchmarks`
- [x] 1.2 Add quality gates to the parent: Spotless (google-java-format), Error Prone, Maven enforcer (dependency convergence, `dta-core`/`dta-gateway` JDK-only), JUnit 5; `mvn verify` green on empty modules
- [x] 1.3 Add ArchUnit test module rules: no package cycles, `core`/`gateway` depend on JDK only, advice classes reference only JDK + `org.otel.agent.gateway`, no `Map<Class<?>,…>` fields in long-lived types
- [x] 1.4 Add the garage example app (`com.example.Garage`, `Car`, `Passenger`, `Result`, HTTP server, OTLP stub) into `dta-example-app` and add `config/agent-config.xml` (rule/selector/when/attribute model) as reference fixture

## 2. Bridge spike (answers S1–S6 in docs/03-classloaders.md §11)

- [x] 2.1 Minimal `dta-gateway` (`EnrichmentGateway`, `ExitHandler`) and a throw-away extension with one advice on `Garage.park` calling the gateway
- [x] 2.2 S1: obtain `Instrumentation` via OTel `InstrumentationHolder` from an extension (plain JVM and OTel Operator image); record result
- [x] 2.3 S2: install gateway into bootstrap via Byte Buddy `ClassInjector.UsingUnsafe.ofBootLoader()`; fallback temp-jar `appendToBootstrapClassLoaderSearch`; test with read-only root filesystem container; record result
- [x] 2.4 S3: JMH fixed-arity `onExit(a0..a3)` vs `@Advice.AllArguments` (ns/op, bytes/op with `-prof gc`); record result
- [x] 2.5 S4: `site` constant via custom advice mapping vs `@Advice.Origin("#m")` + per-class lookup; record result
- [x] 2.6 S5: accessor via `privateLookupIn` + `LambdaMetafactory`; prove generated class is collected with its `URLClassLoader` (leak test); record result
- [x] 2.7 S6: gateway visibility under OSGi/JBoss Modules (effort estimate or "unsupported in v2"); record result
- [x] 2.8 Decision gate: update `design.md` (D3, D5, D6, D11), `classloader-bridge` spec and docs/03 with spike outcomes; remove throw-away spike code

## 3. Core: XML configuration (`org.otel.agent.core.config`)

- [x] 3.1 Secure JDK XML parser (DOCTYPE disallowed, no external entities/DTD/schema) with tests incl. XXE
- [x] 3.2 Document model + strict validation (unknown elements/attributes, namespaces, duplicate sections, blank values, keys unique ≤255, duplicate (class, method), rule without selector) with one test per rule
- [x] 3.3 `<dynamic>` accepts only `<rule>` elements; anything else is rejected with its location
- [x] 3.4 Protected-namespace selectors (`java.*`, `javax.*`, `jdk.*`, `sun.*`, `com.sun.*`, `io.opentelemetry.*`, `net.bytebuddy.*`, `org.otel.agent.*`) disable only that rule with one warning

## 4. Core: paths and value conversion (`core.path`, `core.value`)

- [x] 4.1 Path compiler (`$this`/`$argN` 0..127/`$return`, segments, `[index]`) with error tests
- [x] 4.2 Runtime resolver: indexing (array/List/Iterable), collection flattening, null safety, Map → null
- [x] 4.3 Attribute value conversion (long/double/boolean/string/enum/char/BigInteger/BigDecimal, homogeneous arrays, typed empty arrays, skip others) with table-driven tests

## 5. Core: condition DSL (`core.dsl`)

- [x] 5.1 Lexer (XML-safe operators only; symbolic forms rejected) with tests
- [x] 5.2 Parser → untyped AST (precedence, parentheses, literals, collection literals, functions, bare boolean) with shape tests
- [x] 5.3 Typer (AST + selected Class/Method → typed condition; numeric normalisation; failure categories) with tests
- [x] 5.4 Evaluator: short-circuit, null semantics, division by zero, normalised numeric `in` (fix), precompiled `like`/`ilike`, hash-set `in` for large lists; allocation-free evaluation
- [x] 5.5 Write `docs/07-dsl.md` (EBNF, typing table, null semantics, examples)

## 6. Core: plan, snapshot, binding (`core.plan`, `core.snapshot`, `core.binding`)

- [x] 6.1 Name-level `TransformationPlan` with fingerprint and `PlanDiff` (added/removed/changed selectors)
- [x] 6.2 `Snapshot` compiler (version, plan, compiled rules, untyped conditions; no `Class` references — ArchUnit/test enforced)
- [x] 6.3 `ClassBinder`: find declared selector type in runtime class hierarchy, check method/properties, type conditions, build accessor chains; unknown method/property → rule ignored with one warning per rule and cause
- [x] 6.4 Accessor generation per spike S5 outcome (getter → `isX` → public field), at most once per (member, class, snapshot)

## 7. Gateway (`org.otel.agent.gateway`)

- [x] 7.1 Final `EnrichmentGateway` + `ExitHandler` per spike outcome (JDK types only, no logic, no-op when no handler)

## 8. Extension: bootstrap and instrumentation (`extension.bootstrap`, `extension.instrument`)

- [x] 8.1 `AgentListener.afterAgent` entry: Instrumentation adapter, gateway installer, engine registration; failure → one warning, extension inactive
- [x] 8.2 `InstrumentationModule` + `TypeInstrumentation`: type matcher from current plan (name prefilter + `hasSuperType`), method matcher per (type, method) pair, exclusions (constructors, static, abstract, native, bridge, synthetic)
- [x] 8.3 Advice variants (void/value × fixed-arity/all-args per S3) with `site` constant per S4; `suppress = Throwable`
- [x] 8.4 Integration test: only selected methods carry advice; same method name on another configured class is not instrumented (fix)

## 9. Extension: engine and telemetry (`extension.engine`, `extension.telemetry`)

- [x] 9.1 `EnrichmentEngine` implements `ExitHandler`: volatile snapshot, static `ClassValue` with version-tagged per-class holder, per-site rules
- [x] 9.2 Each matching selector evaluated with its own rule's condition (fix); condition false skips all work
- [x] 9.3 Re-entrancy guard via OTel `CallDepth`
- [x] 9.4 `SpanWriter`: prebuilt static `Attributes`, precomputed typed `AttributeKey`s, current span vs `INTERNAL` fallback span `dynamic-trace.enrichment`; per-attribute failure isolation

## 10. Extension: reload (`extension.reload`)

- [x] 10.1 Single poller thread (daemon, TCCL null), interval from `DYNAMIC_TRACE_CONFIG_RELOAD_INTERVAL` (invalid → 5 s), mtime/size check before read
- [x] 10.2 Reload coordinator: valid → publish then retransform by plan diff; invalid → keep, warn once per file version; unreadable → keep, warn once per episode; missing/empty → disable and restore bytecode
- [x] 10.3 `Retransformer`: scan loaded classes against old ∪ new plan; degraded mode without Instrumentation (no-op advice, one warning)
- [x] 10.4 "Selector class not loaded (yet)" warning after each load/reload; rule activates when the class loads later

## 11. Diagnostics (`extension.diag`)

- [x] 11.1 Debug log active only with `otel.javaagent.debug`; lifecycle events (loaded/rejected config, accepted/ignored rules, bindings, retransforms, reload timing); never attribute values; free when off

## 12. Memory safety

- [x] 12.1 Classloader-unload test: load sample classes in a `URLClassLoader`, instrument, enrich, reload several times, drop loader, assert collected
- [x] 12.2 Reload soak test: 10,000 alternating reloads under load; heap-after-GC, metaspace, class count, thread count plateau
- [x] 12.3 ThreadLocal / poller-TCCL / no-retained-application-object tests
- [x] 12.4 Write `docs/09-memory-safety.md` (L1–L12 catalogue, rules, tests)

## 13. End-to-end integration

- [x] 13.1 Test harness: example app + OTel agent 2.30.x + extension + OTLP stub; assert attributes on spans for the reference config
- [x] 13.2 Scenarios: condition true/false, fallback span, void method, `$return`, flattening, invalid `<when>` disables rule, unknown property ignored, reload add/remove/attribute-only, file unreadable/missing
- [x] 13.3 CI matrix over supported OTel agent versions

## 14. Benchmarks (`dta-benchmarks`)

- [x] 14.1 JMH: gateway dispatch, condition false/true, no matching rule, path resolution, span writing (ns/op, bytes/op)
- [x] 14.2 k6 macro benchmark: modes baseline (OTel only) / disabled / enabled + verify, Markdown report (no hard-coded absolute paths)
- [x] 14.3 Attribute scaling series (0/5/20, JMH), startup time, reload under load, heap/class check after a 1M-request run with periodic reloads
- [x] 14.4 Record first results in `benchmarks/` (three runs, median); write `docs/08-performance.md` and `docs/11-benchmarking.md`
- [x] 14.5 Moved to `openspec/BACKLOG.md`: macro scaling series over 1/10/100 selected methods and a multi-hour soak (decided 2026-10-09)

## 15. Packaging and distribution

- [x] 15.1 Shaded extension jar `dynamic-trace-agent.jar` (AutoService SPI merged, core + gateway included)
- [x] 15.2 Docker image (busybox base, `/extensions/dynamic-trace-agent.jar`) and GitHub Actions (snapshot, PR verify, release tag, GHCR retention)
- [x] 15.3 Kubernetes example: `Instrumentation` CR with pinned agent image, ConfigMap with reference config, Deployment mount without `subPath`

## 16. Documentation and traceability

- [x] 16.1 Write remaining docs: 00-overview, 01-glossary, 04-flows, 05-state-machines, 06-config-xml, 10-code-guidelines; finalise 02 and 03
- [x] 16.2 Root README (what, quick start, Operator deployment, config reference link)
- [x] 16.3 Spec scenario traceability (docs/10-code-guidelines.md §7): every scenario mapped to a test, benchmark or review
