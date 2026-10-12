# 01 — Glossary

| Term | Meaning | In code |
|---|---|---|
| **Rule** | One `<rule>`: selectors, optional condition, attributes. | `RuleDefinition`, `CompiledRule` |
| **Selector** | A `(class, method)` pair a rule applies to. Several selectors in a rule are OR alternatives. Applies to the class, its subclasses and implementations. | `RuleDefinition.Selector` |
| **Exit point** | An instrumented method whose exit triggers enrichment. | — |
| **Condition** | The `<when>` expression; decides per call whether the rule enriches. | `Expression` (parsed), `Condition` (typed) |
| **Static attribute** | `<static><attribute key value/>`: written on every enrichment event. | `ConfigDocument.StaticAttribute` |
| **Dynamic attribute** | `<attribute key path/>` in a rule: value read at the exit. | `CompiledAttribute`, `BoundAttribute` |
| **Path** | `$this`, `$return` or `$argN` followed by properties, e.g. `$arg0.passengers[1].name`. | `CompiledPath`, `PathReader` |
| **Enrichment event** | One exit for which at least one rule's condition holds: static attributes once, plus each matching rule's dynamic attributes. | `MethodEnrichment.enrich` |
| **Fallback span** | The `INTERNAL` span `dynamic-trace.enrichment` created when there is no current span. | `SpanWriter` |
| **Plan** | Which `(class, method)` pairs carry advice, by name, plus their argument mode. | `TransformationPlan` |
| **Argument mode** | Fixed (pass `$arg0..$arg3` individually) or all arguments (array, when a rule reads `$arg4`+). | `PlanEntry.allArguments` |
| **Snapshot** | One complete, immutable configuration: plan, compiled rules, static attributes. Names only. | `Snapshot` |
| **Published state** | The snapshot as the engine uses it, with prepared attribute keys and static `Attributes`. | `EngineState` |
| **Binding** | A snapshot's rules bound to one runtime class and method: typed condition, readers. | `MethodBinding`, `MethodEnrichment` |
| **Site** | Constant id of an instrumented method, baked into its advice. | `SiteRegistry` |
| **Advice** | Byte Buddy code copied into the instrumented method's bytecode. | `ExitAdvice` |
| **Gateway** | The two classes in the bootstrap classloader that advice calls; the only bridge to the engine. | `EnrichmentGateway`, `ExitHandler` |
| **Engine** | Receives exits via the gateway and enriches spans. | `EnrichmentEngine` |
| **Retransformation** | Re-weaving already loaded classes after the plan changed. | `Retransformer` |
| **Degraded mode** | No JVM `Instrumentation` available: loaded classes are not retransformed. | `Retransformer` |
| **Ignored rule** | A rule disabled with one warning (protected namespace, invalid condition, missing method or property). | `IgnoredRule` |
| **Protected namespace** | Packages rules may not select: `java.`, `javax.`, `jdk.`, `sun.`, `com.sun.`, `org.w3c.`, `org.xml.`, `io.opentelemetry.`, `net.bytebuddy.`, `org.otel.agent.`. | `ProtectedNamespaces` |
