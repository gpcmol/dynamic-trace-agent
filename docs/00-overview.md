# 00 — Overview

## Problem

Something is wrong with order `847392` in production. The trace is there, but the business values
you need — customer, payment, result — were never instrumented. Normally:

```
change code → build → deploy → wait for the problem → remove the instrumentation again
```

With this extension:

```
edit a rule in a ConfigMap → the running JVM picks it up → matching calls enrich their spans
                                                         → the tail sampler keeps those traces
remove the rule when the incident is over → the instrumentation is gone again
```

No code change, rebuild, redeploy or restart.

## What it is

An **extension for the OpenTelemetry Java agent**. It adds attributes to spans that already exist
(or, outside a trace, one `INTERNAL` span), read from arguments, the receiver or the return value of
selected methods, optionally only when a condition holds:

```xml
<rule>
  <selector><class name="com.example.Garage"/><method name="park"/></selector>
  <when>$arg0.brand in ['bmw', 'audi'] and $arg0.mileage gt 0</when>
  <attribute key="brand"  path="$arg0.brand"/>
  <attribute key="status" path="$return.status"/>
</rule>
```

```
WHERE  selectors   which methods?
WHEN   condition   which calls?
WHAT   attributes  which values, on the existing span?
LIVE   XML file    changed while the JVM keeps running
```

## Audience

- **Operators / SREs** writing rules during incidents: [06-config-xml.md](06-config-xml.md),
  [07-dsl.md](07-dsl.md).
- **Platform engineers** deploying it with the OpenTelemetry Operator:
  [02-architecture.md](02-architecture.md) §2, `deploy/kubernetes/`.
- **Maintainers** (Java developers, not agent experts): start with
  [03-classloaders.md](03-classloaders.md), then [10-code-guidelines.md](10-code-guidelines.md).

## Scope

In scope: enrichment of existing spans, conditional rules, live reload with retransformation,
OpenTelemetry Operator deployment, read-only root filesystems, OSGi/JBoss boot delegation.

Not in scope (see `openspec/BACKLOG.md`): creating spans beyond the fallback span, package /
annotation / wildcard selectors, overload selection, `Map` access, a second configuration format,
JMX, an own backend.

## Guarantees

| Guarantee | How | Where |
|---|---|---|
| The application is never affected | advice suppresses all throwables; every failure is contained per attribute | `ExitAdvice`, `MethodEnrichment` |
| No memory leaks on instrumentation or reload | references only point from application classes to agent data | [09-memory-safety.md](09-memory-safety.md) |
| Best achievable performance | 13 ns and 0 bytes per call when a condition is false | [08-performance.md](08-performance.md) |
| Invalid configuration never breaks a valid one | last valid configuration stays active | [05-state-machines.md](05-state-machines.md) |
