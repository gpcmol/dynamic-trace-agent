# 10 — Code guidelines

Written for a Java developer maintaining this project without AI assistance. Most rules are
enforced by the build (`mvn verify`); the rest are checked in review.

## 1. Build and gates

```bash
mvn verify                       # everything: format, Error Prone, unit, ArchUnit, memory, end-to-end
mvn spotless:apply               # format before committing
mvn -pl dta-core test            # fast loop on pure logic
```

| Gate | Tool | Fails on |
|---|---|---|
| formatting | Spotless (google-java-format) | unformatted code |
| bug patterns | Error Prone (warnings shown, errors fail) | e.g. wrong format strings, unclosed resources |
| dependencies | Maven enforcer | `dta-core` / `dta-gateway` depending on anything but the JDK |
| architecture | ArchUnit (`ArchitectureTest`) | package cycles, core/gateway depending outside the JDK, advice code using non-gateway classes, `Map<Class,…>` fields, `Class` fields in config/plan/snapshot/expressions |
| memory | `ClassLoaderUnloadTest`, `ReloadSoakTest`, `PollerThreadTest` | leaks ([09-memory-safety.md](09-memory-safety.md)) |
| behaviour | `dta-integration-tests` (real OTel agent) | end-to-end regressions |

## 2. Where code goes

| You want to … | Package |
|---|---|
| change the XML format or its validation | `core.config` |
| change path syntax or how values are read | `core.path` |
| change the condition language | `core.dsl` (+ `docs/07-dsl.md`) |
| change what gets instrumented | `core.plan`, `extension.instrument` |
| change how rules attach to classes | `core.binding`, `extension.engine.ClassBindings` |
| change what is written on spans | `core.value`, `extension.telemetry` |
| change reload behaviour | `extension.reload` |
| change start-up | `extension.bootstrap` |

Logic that does not need OpenTelemetry or Byte Buddy belongs in `dta-core`, where it can be tested
with plain JUnit.

## 3. Style

- One responsibility per class; a class comment says what it is for and, where relevant, how it
  behaves on the hot path and for memory.
- Immutable data as `record`s with validating compact constructors; `List.copyOf` for collections.
- Explicit names over abbreviations (`selectedRules`, not `sr`). No "util" packages.
- `final` parameters and locals, as in the existing code.
- Hot path (anything called from `EnrichmentEngine.onExit`): no allocation unless measured and
  justified, no locks, no lookups by name, no logging except `AgentLog.debug` with a lambda.
- Cold path (load, reload, bind): clarity over speed.
- Never let an exception reach the application; catch `RuntimeException | LinkageError` at the
  boundaries that are already established (engine, attribute writer, condition evaluation).
- Never log attribute values (they may be personal data).

## 4. Tests

```
            ┌───────────────────────────────┐
            │  dta-integration-tests (11)   │  real agent, real JVM, live reloads
            ├───────────────────────────────┤
            │  dta-extension (59)           │  engine with OTel SDK, matchers, reload, memory
            ├───────────────────────────────┤
            │  dta-core (356)               │  parser, paths, DSL, plan, snapshot, binder
            └───────────────────────────────┘
```

- Every spec scenario has a test; name tests after the behaviour (`falseConditionTouchesNothing`).
- Test fixtures that are rule targets must live **outside** `org.otel.agent` (protected namespace),
  e.g. `com.example.*` in the test sources.
- A bug fix starts with a failing test.
- Performance changes on the hot path come with JMH numbers before and after
  ([11-benchmarking.md](11-benchmarking.md)).

## 5. Changing the gateway

The gateway (`dta-gateway`) is the contract between bytecode woven into application classes and the
engine. Changing a signature breaks classes woven by an older version until they are retransformed.
Read [03-classloaders.md](03-classloaders.md) §10 first and treat it as a breaking change.

## 6. Upgrading dependencies and the OpenTelemetry agent

All versions are properties in the root `pom.xml`. `lcm.sh` updates them with the
`versions-maven-plugin`:

```bash
./lcm.sh --check   # show what is outdated
./lcm.sh           # update within the current major version
./lcm.sh --major   # update, including major-version jumps
```

The OpenTelemetry versions stay consistent by construction: the SDK version is not a property but
comes from the BOM of the agent (`opentelemetry-instrumentation-bom-alpha`), and the script copies a
new `otel.agent.version` to the CI matrix (`.github/workflows/ci.yml`), the README and
`deploy/kubernetes/instrumentation.yaml`.

After an update:

1. `mvn verify` — the integration tests run against the real agent and exercise both internal APIs
   (`InstrumentationHolder`, `BootstrapPackagesConfigurer`).
2. Check that the `autoinstrumentation-java` image exists for the new agent version before
   releasing.

A new dependency: give it a version property and declare it in the root `dependencyManagement`
(plugins: `pluginManagement`); the plugin then picks it up by itself.

Docker base images and GitHub Actions are updated by Dependabot (`.github/dependabot.yml`).
`google-java-format.version` is updated by hand, together with a major version of the Spotless plugin:
a newer formatter does not run on the current plugin and reformats the sources.

## 6a. Security rules

The rule file is untrusted input ([06-config-xml.md](06-config-xml.md) §6). When changing the
parser, paths or the condition language:

- Never add a construct that names code to run: no method calls with arguments, no static access,
  no class loading by name, no script or expression engine.
- Every new piece of input needs a bound (length, count, depth) and a test for the bound.
- A new way to reach objects must go through `PropertyAccessors`, so `ForbiddenTypes` applies.
- Text from the file may be quoted in messages; it reaches the log only through `AgentLog`.

## 7. Spec scenario coverage

Every scenario of the specs is covered as follows (A = automated test, B = benchmark,
M = measured manually in the spike, R = review / by construction):

| Spec | A | B / M / R |
|---|---|---|
| xml-configuration (13) | all 13 | — |
| value-path-resolution (13) | all 13 | — |
| condition-dsl (8) | all 8 | — |
| attribute-enrichment (7) | all 7 | — |
| selector-instrumentation (8) | 7 | void advice: M (spike S7, JMH run with the agent) |
| runtime-reload (9) | 7 | two web applications: R (bindings are per class); selector added without `Instrumentation`: R |
| classloader-bridge (11) | 6 | read-only root FS, `java.*`-only classloader: M (spike S2, S6); reload during traffic: B; no allocation without escape analysis: M (spike S3); developer reads the code: R |
| hot-path-performance (8) | 4 | JMH allocation profile, condition true: B; no locks under concurrency: R |
| memory-safety (7) | 6 | many distinct runtime classes: R (one accessor per class, never evicted) |
| diagnostics (4) | 3 | no values logged: R |
| packaging-and-distribution (8) | 5 | new agent release: CI matrix; release tag: workflow (runs on GitHub) |
| benchmarking (4) | — | B (all), fresh checkout: R |

Open (`openspec/BACKLOG.md`): macro scaling over 1/10/100 selected methods; multi-hour soak with heap and
metaspace over time.
