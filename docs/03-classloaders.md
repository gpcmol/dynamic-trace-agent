# 03 — Classloaders: how configuration reaches the advice

> **Read this before touching `dta-gateway`, the advice classes, or anything that holds a `Class`.**
> It is the one part of this project that is not ordinary Java. Everything else is.

The design was validated by the bridge spike of 2026-10-09; measured outcomes are in §11.

---

## 1. The problem in one sentence

The code that must run when `Garage.park()` returns lives *inside the application's class*, but
the configuration and the engine that know what to do live in a *different classloader the
application cannot see*.

---

## 2. Which classloaders exist

When a JVM runs with the OpenTelemetry Java agent and our extension, these classloaders exist:

```
                        ┌──────────────────────────────────────────┐
                        │        Bootstrap classloader (null)      │
                        │  java.*  ·  OTel bootstrap classes       │
                        │  (io.opentelemetry.javaagent.bootstrap.*)│
                        │  shaded OTel API/context                 │
                        │  + OUR GATEWAY (dta-gateway)   ◄── we put│
                        └───────────────▲──────────────────▲───────┘   it here
                                        │ parent           │ parent
                 ┌──────────────────────┴───┐   ┌──────────┴──────────────────────┐
                 │  AgentClassLoader        │   │  Platform / System classloader  │
                 │  OTel agent internals,   │   │  application classes            │
                 │  Byte Buddy, SDK         │   │  (com.example.Garage, Car, …)   │
                 └───────────▲──────────────┘   └──────────▲──────────────────────┘
                             │ parent                      │ parent
                 ┌───────────┴──────────────┐   ┌──────────┴──────────────────────┐
                 │  ExtensionClassLoader    │   │  (app servers: one loader per   │
                 │  OUR EXTENSION           │   │   web application, OSGi bundle, │
                 │  dta-core + dta-extension│   │   JBoss module, …)              │
                 └──────────────────────────┘   └─────────────────────────────────┘
```

A classloader can see its own classes and those of its **parents**, never those of siblings or
children. That gives this visibility table:

| Code running in …            | sees bootstrap | sees agent | sees extension | sees application |
|------------------------------|:--------------:|:----------:|:--------------:|:----------------:|
| bootstrap (our gateway)      | ✔ | ✘ | ✘ | ✘ |
| extension (our engine)       | ✔ | ✔ | ✔ | only via `Class` objects it is handed |
| application (`Garage`)       | ✔ | ✘ | ✘ | ✔ |

The **only** place both the application and our extension can see is the bootstrap classloader.

---

## 3. What "advice" really is

Byte Buddy *advice* is not called like a method. Its bytecode is **copied into** the target method
when the class is loaded (or retransformed). Conceptually, `Garage.park` becomes:

```java
// what the developer wrote               // what actually runs after instrumentation
public Result park(Car car) {             public Result park(Car car) {
  boolean ok = parkedCars.add(car);         boolean ok = parkedCars.add(car);
  return new Result(ok, …);                 Result r = new Result(ok, …);
}                                           // ── copied advice code ────────────────────
                                            try {
                                              EnrichmentGateway.onExit(SITE, this, car, r);
                                            } catch (Throwable ignored) { }
                                            // ──────────────────────────────────────────
                                            return r;
                                          }
```

The copied code is now **part of `Garage`**. When it refers to a class (`EnrichmentGateway`), the
JVM asks *`Garage`'s classloader* to find it. So:

> Advice can only reference classes that the **application's** classloader can see:
> JDK classes and bootstrap classes. It cannot reference the engine in the extension classloader.

Without a bridge, every class the advice touches (directly or indirectly) would have to be copied
into every application classloader.

---

## 4. Three ways to bridge the gap

| | Inject the engine | Serialise the rules | **Gateway (this design)** |
|---|---|---|---|
| Idea | Copy the whole engine into every application classloader | Store rules as strings in a JVM-wide place (e.g. `System` properties) and parse them in the advice | One tiny **gateway** in the bootstrap classloader; the engine stays in the extension |
| Classes to keep in a list | every engine class, incl. `Foo$1` inner classes | few | **0** (the gateway is installed as a whole) |
| Cost per call | normal | string lookup and parsing per call | one volatile read + one interface call |
| State | one copy per application classloader | global strings | **one** engine, **one** snapshot per JVM |
| Typical failure | `NoClassDefFoundError` when a class is missing from the list | slow, untyped | gateway not installable → extension disables itself, application unaffected |

---

## 5. The design: a gateway in bootstrap

```
   APPLICATION CLASSLOADER            BOOTSTRAP                    EXTENSION CLASSLOADER
 ┌───────────────────────────┐   ┌──────────────────────┐   ┌─────────────────────────────────┐
 │ Garage.park()             │   │ EnrichmentGateway    │   │ EnrichmentEngine                │
 │  … original code …        │   │  static volatile     │   │   implements ExitHandler        │
 │  [advice] ────────────────┼──►│  ExitHandler handler ┼──►│   current Snapshot (volatile)   │
 │                           │   │                      │   │   bindings per class            │
 │                           │   │ interface ExitHandler│   │   span writer, reload, …        │
 └───────────────────────────┘   └──────────────────────┘   └─────────────────────────────────┘
        calls gateway                knows NOTHING              registers itself as handler
        (only JDK + gateway)         but JDK types              at agent start
```

The gateway module (`dta-gateway`) contains exactly two types and no logic:

```java
// sketch — not final code
public interface ExitHandler {                      // only JDK types in signatures
  void onExit(int site, Object self, Object returned, Object a0, Object a1, Object a2, Object a3);
  void onExitAllArgs(int site, Object self, Object returned, Object[] arguments);
}

public final class EnrichmentGateway {
  private static volatile ExitHandler handler;      // null = engine absent → no-op
  public static void install(ExitHandler h) { handler = h; }
  public static void onExit(…) { ExitHandler h = handler; if (h != null) h.onExit(…); }
}
```

Rules for the gateway — enforced by ArchUnit and code review:

1. **Only JDK types** (and its own two types) in fields and signatures.
2. **No logic**, no caches, no state except the single `handler` reference.
3. **Never changes shape** in a release unless unavoidable — it is the contract between bytecode
   already woven into application classes and the engine.
4. Exceptions never leave `onExit` (advice is additionally `suppress = Throwable.class`).

Why `Object a0..a3` and a separate `onExitAllArgs`? `@Advice.AllArguments` creates an `Object[]`
on every call; the JIT can usually remove it, but not reliably (measured: 24 B/op when escape
analysis fails). Most selected methods only need `$this`, `$return` and the first few arguments, so
the common case passes them individually and allocates nothing — by construction (S3). Methods whose
rules use `$arg4` or higher use the array variant. One advice serves void and value methods: the
return parameter is dynamically typed and `null` for void (S7).

`site` is a constant baked into the advice per instrumented method (Byte Buddy custom advice
mapping, supported by OTel's `TypeTransformer.applyAdviceToMethod(…, customMapping, …)`). It lets the
engine find the method's rules without comparing strings: ~1 ns per call versus ~5 ns for
`@Advice.Origin("#m")` + a per-class lookup by name (S4).

### How the gateway gets into bootstrap

The extension must do this itself: in our deployment (OpenTelemetry Operator) there is no room for
a second `-javaagent`.

```
 dynamic-trace-agent.jar
 ├── org/otel/agent/core/…            loaded by the ExtensionClassLoader
 ├── org/otel/agent/extension/…       loaded by the ExtensionClassLoader
 └── dta-gateway/org/otel/agent/gateway/*.class
        ▲ NOT on the package path: the extension can never load its own copy
        │
        └── at agent start: read as bytes ──► ClassInjector.UsingUnsafe.ofBootLoader()
                                              defines them in the bootstrap classloader
```

Chosen: **Byte Buddy `ClassInjector.UsingUnsafe.ofBootLoader()`** (Byte Buddy ships inside the
agent). No disk write, ~12 ms, works with a read-only root filesystem (S2). Rejected alternatives:
`appendToBootstrapClassLoaderSearch` (needs a temp jar on a writable disk) and OTel's "indy" mode
(designed for this problem, but experimental in OTel 2.x).

Because the gateway classes are not loadable from the extension jar, the engine code that refers to
them must only run **after** injection. `AgentListener.afterAgent` therefore installs the gateway
first and only then touches a class that references gateway types.

`Instrumentation` itself comes from the OTel agent's bootstrap
`io.opentelemetry.javaagent.bootstrap.InstrumentationHolder` (verified present in agent 2.30.0;
internal API → wrapped in one adapter class).

---

## 6. Lifecycles

### 6.1 Agent start (before the application's `main`)

```
 OTel agent premain
   └─ loads extension (ExtensionClassLoader)
        └─ AgentListener.afterAgent()                                   [dta-extension]
             1. Instrumentation  ← InstrumentationHolder (adapter)
             2. install gateway classes into bootstrap                  (§5 option A/B/C)
             3. EnrichmentGateway.install(engine)
             4. read config file → parse → compile Snapshot             (names only, §7)
             5. engine.publish(snapshot)
             6. start the single poller thread (daemon, TCCL = null)
   └─ application main() starts; classes load …
```

Step 4 needs **no application classes**: the snapshot is expressed in class/method *names*. This
means no timers or retries are needed to wait for application classes.

If any of steps 1–3 fails, the extension logs one warning and stays inactive. The application is
unaffected.

### 6.2 A selected class loads

```
 JVM loads com.example.Garage
   └─ OTel agent asks our TypeInstrumentation: typeMatcher(Garage)?
        └─ current plan: names {com.example.Garage#park}  → hasSuperType match ✔
   └─ method matcher: park ✔  (per (type, method) pair — not by name across all classes)
   └─ Byte Buddy copies advice into Garage.park, with SITE constant
```

### 6.3 `Garage.park` returns (the hot path)

```
 Garage.park [advice]
   └─ EnrichmentGateway.onExit(SITE, this, null, car, …, result)       bootstrap, 1 volatile read
        └─ EnrichmentEngine.onExit                                       extension
             1. snapshot = current                                      volatile read
             2. bindings = CLASS_BINDINGS.get(receiver.getClass())       ClassValue (per class)
                └─ first time / new snapshot version → bind (§7)        cold, once
             3. rules for SITE; for each rule:
                  condition false → next rule                          (allocation-free)
                  frame slot of this thread (also the re-entrancy guard) → span (current or fallback)
                  write prebuilt static Attributes, then dynamic attributes
```

### 6.4 Reload

```
 poller: file changed (mtime/size) → read → parse → compile Snapshot S2
   ├─ invalid / unreadable  → keep S1, warn once, retry next poll
   └─ valid:
        1. engine.publish(S2)                    atomic; in-flight calls finish on S1
        2. diff(S1.plan, S2.plan)
             ├─ same fingerprint (only <when>/attributes changed) → done, 0 retransforms
             └─ changed → retransform loaded classes matching S1∪S2 affected selectors
                          (added: advice appears · removed: original bytecode restored)
```

Publishing **before** retransforming is correct in both directions: a newly added selector has its
rules ready before its advice appears; a removed selector's advice already finds no rules (no-op)
before it is stripped.

---

## 7. Where data lives — and why that prevents leaks

Three levels of data, each with one owner:

```
 ConfigDocument      parsed XML, syntax-checked                 extension, transient
      │ compile
      ▼
 Snapshot            plan (names) + compiled rules (untyped)   extension, ONE active, volatile
      │ bind (per runtime class, lazily)                        NO Class references at all
      ▼
 ClassBindings       typed conditions, accessors, attribute     attached TO the application
                     keys for one runtime class                 class via ClassValue
```

**The golden rule: references may point from the application to the agent, never back.**

```
  application Class ──(ClassValue entry)──► ClassBindings ──► Snapshot data       ✔ allowed
  gateway / engine / poller / snapshot ──────────────────────► application Class  ✘ forbidden
```

Consequences:

- When a web application is undeployed, its classes and their `ClassValue` entries become
  unreachable together; nothing in the agent keeps them alive.
- The engine uses **one** static `ClassValue` for the lifetime of the JVM (not one per reload).
  Its per-class value is a small holder that remembers the published configuration it was built
  for; the first call after a reload rebuilds it once. Old bindings are dropped as classes are used or unloaded.
- Accessors (getter/field readers) are plain `MethodHandle`s stored in the class binding, i.e. in
  the `ClassValue` entry of the application class itself. Measured: the application classloader is
  collected (S5). Generating accessor classes with `LambdaMetafactory` is not possible here: from
  the extension's lookup the generated class cannot see the application class, and from the
  application class's lookup LMF rejects the caller.
- Retransformation does not need a list of "touched classes": it scans loaded classes against the
  old and new plan. Nothing to leak.

The full leak catalogue (L1–L12) and its tests live in [09-memory-safety.md](09-memory-safety.md) and the
`memory-safety` spec.

---

## 8. Degraded modes

| Situation | Behaviour |
|---|---|
| Gateway cannot be installed | Extension logs one warning and does nothing. No advice is woven. |
| `Instrumentation` unavailable | Classes loaded later follow the current plan. Already-loaded classes are not retransformed: removed rules become no-ops via the snapshot; added selectors apply after restart (one warning). |
| Binding fails for a class (e.g. unknown property) | That rule is disabled for that class; one warning. Other rules and classes unaffected. |
| Engine throws | Caught inside the engine; advice is `suppress = Throwable`. The application method returns normally. |

---

## 9. Application servers, OSGi, JBoss Modules

Plain JVMs, Spring Boot fat jars and Tomcat delegate unknown packages to the bootstrap
classloader, so the gateway is visible everywhere. **OSGi and JBoss Modules do not**: they only
delegate configured packages. OTel's `BootDelegationInstrumentation` instruments
`ClassLoader.loadClass` to delegate a list of "bootstrap packages" to bootstrap first. The extension
adds `org.otel.agent.gateway` to that list through OTel's `BootstrapPackagesConfigurer` (internal
tooling SPI, behind one adapter class). Verified with a classloader that only delegates `java.*`
(S6); not yet tested on a real OSGi or JBoss server.

---

## 10. FAQ for maintainers

**I added a class to the engine. Do I need to register it somewhere?**
No. Only the gateway crosses the boundary, and it is installed as a whole. That is the point.

**Can the advice call something besides the gateway?**
No. Advice may reference JDK classes and `EnrichmentGateway` only. ArchUnit enforces this.

**Can I store the receiver / an argument for later?**
No. Application objects may only be referenced during one `onExit` call (memory-safety spec).

**Can I put a `Map<Class<?>, …>` in the engine?**
No. Per-class data goes into the engine's `ClassValue`. A map keyed by `Class` in a long-lived
object pins application classloaders.

**I need to change the gateway's method signature.**
Treat it as a breaking change: classes woven by an earlier release call the earlier signature until they
are retransformed. Discuss first.

**How do I test classloader behaviour?**
`dta-extension` has tests that load sample classes in a throw-away `URLClassLoader`, instrument
them, reload configs, drop the loader and assert it is garbage-collected.

---

## 11. Spike: questions and outcome

Spike run 2026-10-09 on JDK 21.0.5 (macOS arm64) with OTel Java agent 2.30.0 (Byte Buddy 1.18.11).
Raw JMH output: `benchmarks/spike-2026-10-09.md`.

| # | Question | Outcome |
|---|---|---|
| S1 | Is `InstrumentationHolder.getInstrumentation()` usable from an extension? | **Yes.** Non-null in `AgentListener.afterAgent`, `isRetransformClassesSupported() = true`. Tested with the Operator's mechanism (`JAVA_TOOL_OPTIONS=-javaagent…` + `OTEL_JAVAAGENT_EXTENSIONS=<dir>`), locally and in a container. |
| S2 | Gateway into bootstrap without disk writes? | **Yes, option A.** `ClassInjector.UsingUnsafe.ofBootLoader().injectRaw(…)` defines the gateway in bootstrap in ~12 ms; the extension resolves the *same* class. Works in a container with `--read-only` root filesystem. Gateway classes are embedded as resources under `dta-gateway/` (not on the package path) so the extension can never load its own copy. |
| S3 | Fixed arity vs `@AllArguments` | With escape analysis both are 0 B/op (~1.0 ns vs 0.43 ns baseline). **Without** escape analysis `@AllArguments` costs 24 B/op and 1.8 ns; fixed arity stays 0 B/op, 0.96 ns. → **Fixed arity is allocation-free by construction**, `@AllArguments` only by JIT luck. Primitive arguments are boxed (16 B/op without EA for an `int` outside the Integer cache). |
| S4 | `site` constant vs `@Origin("#m")` + per-class lookup | **Site constant: ~0.97 ns; by name: ~4.96 ns** (ClassValue + map lookup). Custom advice mapping works through OTel's `TypeTransformer.applyAdviceToMethod(…, customMapping, …)`. |
| S5 | Accessor generation without leaks | `LambdaMetafactory` is **not possible** for application classes: with the extension's lookup the generated class cannot see the application class (`NoClassDefFoundError`); with `privateLookupIn(appClass)` LMF rejects the caller (no full privilege). **Plain `MethodHandle`** stored in the engine's static `ClassValue` works and the application classloader **is collected**. |
| S6 | OSGi / JBoss Modules | **Supported** via OTel's internal SPI `BootstrapPackagesConfigurer` (artifact `opentelemetry-javaagent-tooling`, compile-only): OTel's `BootDelegationInstrumentation` then makes every classloader delegate `org.otel.agent.gateway` to bootstrap. Proven with a loader that only delegates `java.*` (visible with the SPI, invisible without). Not tested on a real OSGi/JBoss server. |
| S7 | (extra) One advice for void and value methods? | **Yes.** `@Advice.Return(typing = DYNAMIC) Object` is accepted on void methods (value `null`); no separate void advice needed. |
