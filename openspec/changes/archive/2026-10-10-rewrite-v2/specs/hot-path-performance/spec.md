## ADDED Requirements

### Requirement: Cost only where configured
The agent SHALL add zero per-call cost to methods that are not selected, and zero cost to any
method when no rule is active (no advice installed).

#### Scenario: No rules
- **WHEN** the configuration has no rules
- **THEN** no application method carries advice

### Requirement: No name-based lookups per call
On a selected method exit the agent SHALL reach the bound rules without string comparison, map
lookup by name, or reflection; per-call dispatch SHALL be bounded by a volatile snapshot read plus
a per-class lookup (`ClassValue` or equivalent).

#### Scenario: Selected method called
- **WHEN** `Garage.park` exits
- **THEN** no `String`-keyed or `(Class, String)`-keyed map is consulted

### Requirement: Allocation-free when the condition is false
The path "selected method exits, condition false" SHALL allocate nothing beyond what passing the
method context to the gateway requires (no argument array for rules that read at most `$arg3`).

#### Scenario: JMH allocation profile
- **WHEN** the condition-false benchmark runs with `-prof gc`
- **THEN** the reported bytes/op equal the documented context-passing cost (target: 0)

### Requirement: Precomputed enrichment artefacts
Per active snapshot the agent SHALL precompute: OTel `AttributeKey`s per dynamic attribute, the
static attributes as one prebuilt `Attributes` object written in one call, accessor chains per
(rule, runtime class), compiled `like`/`ilike` patterns for literal patterns, and hash-based sets
for large `in` literal lists.

#### Scenario: Condition true
- **WHEN** a rule with 5 static and 10 dynamic attributes enriches a span
- **THEN** no attribute key or accessor is created or looked up by name during the call

### Requirement: No discovery on the hot path
Accessor discovery (reflection, `LambdaMetafactory`) SHALL happen at most once per
(member, runtime class) per snapshot; caches SHALL NOT evict entries that are still in use.

#### Scenario: Many classes
- **WHEN** 5,000 distinct runtime classes pass through a selected interface method
- **THEN** each class triggers discovery once and never again while the snapshot is active

### Requirement: No locks on the hot path
The enrichment path SHALL be lock-free; synchronisation is only allowed on the cold path
(load, reload, retransformation).

#### Scenario: High concurrency
- **WHEN** 200 threads call a selected method concurrently
- **THEN** no thread blocks on an agent monitor

### Requirement: Debug logging is free when off
When debug logging is off, its cost on the hot path SHALL be at most one read of a final field.

#### Scenario: Debug off under load
- **WHEN** 1M selected calls run with debug off
- **THEN** no log message is formatted or allocated

### Requirement: Bounded cold-path cost
Class-load matching SHALL prefilter by class name before hierarchy checks; reload SHALL
retransform only changed footprints; polling SHALL compare modification time and size before
reading content. Startup time, reload duration and retransformation count SHALL be reported by the
benchmarks.

#### Scenario: Unchanged file
- **WHEN** the poller runs and the file's modification time and size are unchanged
- **THEN** the file is not read
