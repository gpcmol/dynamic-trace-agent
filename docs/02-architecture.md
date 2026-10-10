# 02 — Architecture

Requirements live in `openspec/specs/`; this document explains *how* the pieces fit. The classloader boundary has its own document:
[03-classloaders.md](03-classloaders.md).

---

## 1. System context

```
                         edit XML (Git / ConfigMap)
                                   │
                                   ▼
┌────────────────────────────────────────────────────────────┐
│ Pod                                                        │
│  ┌──────────────────────────────────────────────────────┐  │
│  │ JVM                                                  │  │
│  │   application (Garage, Car, …)                       │  │
│  │   OTel Java agent  ── existing spans                 │  │
│  │     └─ our extension ── enriches those spans when    │  │
│  │                         selected methods exit        │  │
│  └───────────────────────────┬──────────────────────────┘  │
└──────────────────────────────┼─────────────────────────────┘
                               │ OTLP
                               ▼
                 OTel Collector (tail sampling on e.g. debug=true)
                               │
                               ▼
                 Tempo / Jaeger / SigNoz
```

The extension never runs its own backend and never changes application behaviour. It adds
attributes to spans that already exist (or, outside a trace, one `INTERNAL` fallback span).

---

## 2. Deployment (OpenTelemetry Operator)

```
 Instrumentation CR (otel-instrumentation)
   java.image:       autoinstrumentation-java:<PINNED VERSION>     ← do not use :latest
   java.extensions:  ghcr.io/gpcmol/dynamic-trace-agent:<version>, dir /extensions
        │
        │ pod annotation instrumentation.opentelemetry.io/inject-java: "true"
        ▼
 Pod garage
 ├─ init container(s) ─ cp agent jar + /extensions/*.jar → shared volume
 └─ container garage
      JAVA_TOOL_OPTIONS = -javaagent:<shared>/javaagent.jar     (set by Operator)
      OTEL_JAVAAGENT_EXTENSIONS = <shared>/extensions           (set by Operator)
      DYNAMIC_TRACE_CONFIG_FILE = /etc/dynamic-trace/agent-config.xml
      volumeMount  /etc/dynamic-trace  ← ConfigMap agent-config (whole dir, NO subPath)
```

- **One artifact**: the extension jar. The only `-javaagent` is the OpenTelemetry agent.
- ConfigMap updates arrive by an atomic symlink swap after the kubelet sync period (typically
  ≤ 1–2 min) plus our poll interval (default 5 s).
- `optional: true` on the ConfigMap volume: no ConfigMap → no file → extension inactive until it
  appears.

---

## 3. Maven modules

```
 dynamic-trace-agent (parent pom: versions, plugins, quality gates)
 │
 ├── dta-core            JDK only. Pure logic, fully unit-testable without an agent.
 │
 ├── dta-gateway         JDK only. Two types. Installed into the bootstrap classloader.
 │
 ├── dta-extension       OTel extension API + Byte Buddy (provided by the agent).
 │                       Depends on dta-core, dta-gateway. Shaded → dynamic-trace-agent.jar
 │
 ├── dta-example-app     The garage application used for demos, integration tests, benchmarks
 │                       (+ the OTLP stub otel.OtlpStub).
 │
 ├── dta-benchmarks      JMH (engine, paths, conditions) and the k6 runner scripts/bench.sh.
 │
 └── dta-integration-tests  End-to-end tests: garage + real OTel agent + packaged extension.
```

Dependency rules (ArchUnit + Maven enforcer):

```
   dta-extension ───► dta-core ───► JDK
         │
         └──────────► dta-gateway ─► JDK
   dta-core    ✘─► OTel, Byte Buddy, dta-extension
   dta-gateway ✘─► anything but the JDK
```

---

## 4. Packages

Base package: `org.otel.agent`; Maven groupId `org.otel.agent`. Each module has its own sub-package
(`org.otel.agent.core.*`, `org.otel.agent.gateway`, `org.otel.agent.extension.*`).

```
dta-core          org.otel.agent.core.
  config      XmlConfigurationParser → ConfigDocument (SecureXmlReader, StrictElement,
              ProtectedNamespaces)
  naming      JavaNames (identifier / class-name syntax)
  path        PathCompiler → CompiledPath; PathReader (+ inline caches); PropertyAccessors
  dsl         Lexer → ExpressionParser → Expression (untyped); ConditionTyper → Condition
  plan        TransformationPlan (names), PlanEntry, PlanDiff
  snapshot    SnapshotCompiler → Snapshot (no Class references)
  binding     RuleBinder: (Snapshot, runtime Class, Method) → MethodBinding
  value       AttributeValues: Java value → String/Long/Double/Boolean/ValueList

dta-gateway       org.otel.agent.
  gateway     EnrichmentGateway, ExitHandler

dta-extension     org.otel.agent.extension.
  bootstrap   AgentStartup (AgentListener), GatewayInstaller, GatewayBootDelegation,
              InstrumentationAccess, ExtensionRuntime (wiring + poller thread)
  instrument  EnrichmentInstrumentationModule, PlanMatchers, ExitAdvice, Site/SiteMappingFactory,
              SiteRegistry, ActivePlan
  engine      EnrichmentEngine (ExitHandler), ClassBindings, MethodEnrichment, ExitFrameSlot
  telemetry   SpanWriter, AttributeKeySet
  reload      ConfigFileWatcher, ReloadCoordinator, Retransformer, ReloadSettings
  diag        AgentLog (warnings; debug only with otel.javaagent.debug)
```

Each package has one responsibility and a `package-info.java` stating it. No package cycles.

---

## 5. Data pipeline

```
 agent-config.xml
      │  XmlConfigurationParser                  syntax + shape errors → whole document rejected
      ▼                                          protected namespaces → that rule ignored
 ConfigDocument
      │  SnapshotCompiler                        paths compiled (invalid → document rejected),
      ▼                                          conditions parsed (invalid → rule ignored)
 Snapshot  {version, plan, rules, staticAttributes, ignoredRules}
      │  ReloadCoordinator → engine.publish + ActivePlan.set + Retransformer
      ▼
 ┌──────── first exit per (runtime class, method, configuration) ────────┐
 │ RuleBinder                                                             │  missing method/
 │   selector class found in the runtime class's hierarchy                │  property, type
 │   condition typed (ConditionTyper), paths get PathReaders              │  error → rule
 └────────────────────────────────────────────────────────────────────────┘  ignored, 1 warning
      ▼
 MethodBinding → MethodEnrichment (+ AttributeKeySets), kept in ClassBindings' ClassValue entry
 of the runtime class, tagged with the published configuration (by identity)
```

---

## 6. Runtime components and threads

```
 ┌───────────────────── poller thread (1, daemon, TCCL = null) ─────────────────────┐
 │ ConfigFileWatcher ─ changed? ──► ReloadCoordinator                                │
 │                                   ├─ parse + compile ─► Snapshot                  │
 │                                   ├─ engine.publish(snapshot)                     │
 │                                   └─ Retransformer.apply(PlanDiff)                │
 └───────────────────────────────────────────────────────────────────────────────────┘

 ┌───────────────────── application threads (any number) ───────────────────────────┐
 │ advice ─► EnrichmentGateway ─► EnrichmentEngine ─► MethodEnrichment ─► SpanWriter │
 │           lock-free, no lookups by name, no allocation when condition is false   │
 └───────────────────────────────────────────────────────────────────────────────────┘

 ┌───────────────────── class-loading threads (JVM) ────────────────────────────────┐
 │ Byte Buddy ─► PlanMatchers read ActivePlan.current() (names only)                 │
 └───────────────────────────────────────────────────────────────────────────────────┘
```

Shared mutable state: the engine's `volatile` published state and `ActivePlan` (written by the
poller, read by everyone), the per-class binding holders (compare-and-set) and the per-thread frame
slot. Nothing on the application threads takes a lock.

---

## 7. Error handling — three levels

| Level | Example | Effect | Reported |
|---|---|---|---|
| Document | malformed XML, unknown element, duplicate key, invalid path | whole document rejected, last valid snapshot stays | once per bad file version |
| Rule | `java.*` selector, (binding) unknown property, invalid `<when>` | that rule disabled (for that class), rest active | once per rule (and class) |
| Call | getter throws, evaluation error, telemetry error | that attribute / condition skipped | never per call (debug counters only) |

The application is never affected at any level.

---

## 8. Related documents

- [03-classloaders.md](03-classloaders.md) — the boundary, the gateway, lifecycles, leak rules
- `openspec/changes/archive/2026-10-10-rewrite-v2/design.md` — decisions and alternatives
- `openspec/specs/*` — normative requirements
