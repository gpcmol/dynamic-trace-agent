## Why

During a production incident the business values needed to understand a trace (customer, order,
result) are often not on the spans. Adding them normally means changing code, building, deploying,
waiting for the problem to recur and removing the code again.

The Dynamic Trace Agent is an OpenTelemetry Java agent extension that adds those values to existing
spans from a rule file that can be changed while the JVM runs. It must be safe and fast enough for
enterprise production, run under the OpenTelemetry Operator, and be maintainable by a Java
developer without AI assistance: explicit boundaries, small packages, documented design.

## What Changes

- Design documentation (`docs/`, Markdown with ASCII diagrams), including an explicit description
  of how configuration crosses from the extension classloader to the application classloader.
- Specifications for every capability below.
- A Java implementation as a Maven multi-module build with quality gates (formatting, static
  analysis, architecture rules, memory and end-to-end tests).
- Behaviour decisions:
  - an invalid `<when>` expression disables its rule (never makes it unconditional);
  - a rule that refers to a class, method or property that does not exist is ignored with one
    warning; the rest of the configuration stays active;
  - an unreadable configuration file keeps the current configuration; a deleted file disables;
  - selectors on JDK, OpenTelemetry, Byte Buddy and agent classes are not allowed;
  - debug logging only when OpenTelemetry agent debugging is on;
  - overloads are not distinguishable; a `(class, method)` pair occurs once per document;
  - properties are read via getters, boolean `isX()`, record component accessors and public fields.
- **Not in scope** (see `openspec/BACKLOG.md`): Map traversal, generic element-type inference,
  overload selection, wider selector kinds, and other future ideas.

## Capabilities

### New Capabilities
- `xml-configuration`: XML document shape, secure parsing, validation rules.
- `value-path-resolution`: `$this` / `$argN` / `$return` paths, accessors, null semantics.
- `condition-dsl`: `<when>` expression language (grammar, typing, evaluation, failure handling).
- `selector-instrumentation`: which methods get advice; transformation plan and its fingerprint.
- `attribute-enrichment`: what happens at method exit; static/dynamic attributes; span handling.
- `runtime-reload`: file polling, atomic publish, last-valid-wins, retransformation diffing.
- `classloader-bridge`: how compiled config reaches advice running in application classloaders.
- `diagnostics`: opt-in debug logging.
- `hot-path-performance`: best achievable per-call cost; precomputation, no lookups/locks/discovery
  on the hot path; budget per call instead of one fixed percentage.
- `memory-safety`: no memory leaks while instrumenting or reloading (classloaders, reloads,
  threads, caches); enforced by automated leak and soak tests.
- `packaging-and-distribution`: Maven build, extension jar, Docker image, CI.
- `benchmarking`: reproducible overhead measurement (k6 macro + JMH micro).

### Modified Capabilities
<!-- none -->

## Impact

- Repository content: `docs/`, `openspec/`, Maven modules, scripts, `deploy/kubernetes/`.
- Target stack: Java 21+, OpenTelemetry Java agent 2.30.x extension API, Byte Buddy via the agent,
  JDK-only core (no runtime dependencies).
- Deployment: one extension image for the OpenTelemetry Operator; environment variables
  `DYNAMIC_TRACE_CONFIG_FILE` and `DYNAMIC_TRACE_CONFIG_RELOAD_INTERVAL`.
