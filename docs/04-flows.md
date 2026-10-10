# 04 — Flows

Sequence views of the four things that happen. The classloader view of the same flows is in
[03-classloaders.md](03-classloaders.md) §6.

## 1. Agent start

```
 JVM            OTel agent              AgentStartup            GatewayInstaller   ExtensionRuntime
  │ premain ──────►│                         │                        │                  │
  │                │ load extensions (SPI)   │                        │                  │
  │                │ install Byte Buddy      │                        │                  │
  │                │ afterAgent(sdk) ───────►│                        │                  │
  │                │                         │ DYNAMIC_TRACE_CONFIG_FILE set?         │
  │                │                         │   no → inactive (debug log), return        │
  │                │                         │ install() ────────────►│ define gateway   │
  │                │                         │                        │ in bootstrap     │
  │                │                         │ start() ─────────────────────────────────►│
  │                │                         │                        │   engine → gateway.install
  │                │                         │                        │   watcher.poll() (first load,
  │                │                         │                        │     synchronous)
  │                │                         │                        │   schedule poller thread
  │ main() ...     │                         │                        │                  │
```

Any failure before `start()` completes: one warning, extension inactive, application unaffected.

## 2. Configuration change

```
 poller thread
  ConfigFileWatcher.poll()
   ├─ file missing            ─► ReloadCoordinator.fileMissing() ─► publish disabled snapshot
   ├─ unreadable              ─► keep current, warn once per episode
   ├─ time & size unchanged   ─► nothing
   └─ new content             ─► ReloadCoordinator.contentChanged(text)
                                   ├─ parse + compile fails ─► keep current, warn
                                   └─ ok:
                                       1. log.resetOnce(); warn ignored rules
                                       2. engine.publish(snapshot)        (volatile swap)
                                       3. ActivePlan.set(plan)            (matchers)
                                       4. Retransformer.apply(PlanDiff)   (only changed footprint)
                                       5. info: "loaded configuration N: …"
  next poll: warn once for selector classes that are not loaded (yet)
```

## 3. Method exit (hot path)

```
 Garage.park(car) returns result
   advice:   EnrichmentGateway.onExit(SITE, this, result, car, null, null, null)
   gateway:  handler == null ? return : handler.onExit(...)
   engine:   state = published state                    disabled ─► return
             enrichment = ClassBindings.lookup(Garage, SITE, state)
                 first call for this class/config ─► RuleBinder.bind ─► MethodEnrichment
             enrichment empty ─► return
             frame = this thread's slot                  in use (nested) ─► return
             for each rule: condition false ─► next rule
                            first true rule: span = current span, or start fallback span
                                             span.setAllAttributes(static attributes)
                            write the rule's dynamic attributes (each isolated)
             end fallback span (if started); release frame slot
```

## 4. Retransformation

```
 Retransformer.apply(diff)
   diff empty                    ─► return 0
   no Instrumentation            ─► warn once (degraded mode), return 0
   for every loaded class: has a class loader, is modifiable, hierarchy contains an affected name
   instrumentation.retransformClasses(all)   (on failure: one by one, warn per failing class)
     └─ JVM re-runs Byte Buddy with the original bytes; PlanMatchers read the NEW ActivePlan:
          added selector   → advice woven in
          removed selector → advice left out (original bytecode)
```
