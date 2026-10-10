# 05 — Configuration lifecycle

Spec `runtime-reload`. Code: `ConfigFileWatcher`, `ReloadCoordinator`.

```
                         variable DYNAMIC_TRACE_CONFIG_FILE not set
            ┌───────────────────────────────────────────────────────────────┐
            ▼                                                               │
     ┌─────────────┐                                                        │
     │  INACTIVE   │  no poller, nothing instrumented                       │
     └─────────────┘                                                        │
                                                                            │
     ┌─────────────┐  valid document              ┌──────────────┐          │
     │  DISABLED   │ ───────────────────────────► │    ACTIVE    │          │
     │ (no rules)  │ ◄─────────────────────────── │ (snapshot N) │ ◄──┐     │
     └─────────────┘  file deleted, or            └──────────────┘    │     │
        ▲    │        valid document without rules     │   │          │     │
        │    │                                          │   │ valid    │     │
        │    │ invalid / unreadable:                    │   │ changed  │     │
        │    └── stay DISABLED, warn                    │   └──────────┘     │
        │                                               │   snapshot N+1     │
        │                                               │                    │
        │       invalid document: stay ACTIVE (N), warn │                    │
        │       unreadable file: stay ACTIVE (N), warn once until readable   │
        │       time/size unchanged: nothing                                 │
        │                                                                    │
   start with variable set ──────────────────────────────────────────────────┘
```

| Event | From | To | Log |
|---|---|---|---|
| valid document with rules | DISABLED / ACTIVE | ACTIVE (new version) | info `loaded configuration N: …` |
| valid document without rules (`<configuration/>`) | ACTIVE | DISABLED (advice removed) | info `loaded configuration N: 0 rule(s) …` |
| invalid document | any | unchanged | warn `configuration rejected, keeping the current one: …` |
| file exists but cannot be read | any | unchanged | warn once per episode `cannot be read … keeping the current configuration` |
| file deleted | ACTIVE | DISABLED (advice removed) | warn `does not exist`, info `disabled: configuration file removed` |
| file appears again | DISABLED | ACTIVE | info `loaded configuration N: …` |
| time and size unchanged | any | unchanged | — |

Versions increase only for valid documents. "Advice removed" needs JVM `Instrumentation`; without
it (degraded mode) the advice stays but finds no rule and does nothing.
