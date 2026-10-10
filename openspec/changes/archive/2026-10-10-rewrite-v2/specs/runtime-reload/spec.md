## ADDED Requirements

### Requirement: Polling
The agent SHALL poll the configuration file every 5 seconds, or every N seconds when
`DYNAMIC_TRACE_CONFIG_RELOAD_INTERVAL` is a positive integer; any other value SHALL fall back
to 5. Unchanged file content SHALL NOT trigger a reload.

#### Scenario: Custom interval
- **WHEN** the variable is `30`
- **THEN** the file is checked every 30 seconds

### Requirement: Atomic publish, last valid wins
A valid changed document SHALL replace the previous immutable runtime state atomically. A
malformed or invalid document SHALL keep the last valid state, be logged, and be retried at the
next poll. Configurations SHALL NOT be merged.

#### Scenario: Invalid XML pushed
- **WHEN** a malformed document replaces a valid one
- **THEN** the previous configuration stays active and a warning is logged

### Requirement: Missing file disables
A deleted configuration file and a valid empty configuration SHALL disable enrichment and remove
the agent's advice from previously transformed classes; enrichment SHALL resume when a valid file
appears again. If no valid configuration was ever loaded, enrichment SHALL stay disabled.

#### Scenario: Rule removed
- **WHEN** the last rule is removed from the XML
- **THEN** the transformed classes are retransformed back to their original bytecode

#### Scenario: File deleted
- **WHEN** the configuration file no longer exists
- **THEN** enrichment is disabled and its advice removed

### Requirement: Unreadable file keeps the current configuration
The agent SHALL keep the current configuration active when the file exists but cannot be read
(e.g. permission denied, I/O error), log exactly one warning for that unreadable episode, and keep
polling; the next valid readable document SHALL be loaded normally.

#### Scenario: Permission denied, then fixed
- **WHEN** the file becomes unreadable for three polls and then readable with valid new content
- **THEN** the current configuration stays active during the three polls, one warning is logged, and
  the new configuration is loaded on the following poll

### Requirement: Per-classloader compilation
The document SHALL be compiled separately for each application classloader that loads selected
classes, because class resolution and type checking depend on the loader. A failure in one loader
SHALL NOT affect other loaders.

#### Scenario: Two web applications
- **WHEN** a selected class exists in only one of two application classloaders
- **THEN** the configuration is active for that loader and reported as not applicable for the other

### Requirement: Minimal retransformation
Reload SHALL retransform only loaded classes whose transformation footprint changed (added,
removed or changed selectors), tracked per application classloader without holding strong
references to application classes. Changes to `<when>` or attributes SHALL apply without
retransformation.

#### Scenario: Attribute-only change
- **WHEN** only an attribute path changes
- **THEN** zero classes are retransformed and in-flight calls observe one consistent snapshot

### Requirement: Instrumentation access and degraded mode
The agent SHALL obtain the JVM `Instrumentation` from the OTel Java agent itself (via one adapter
class around the agent's bootstrap `InstrumentationHolder`). If it is
unavailable, the agent SHALL run in degraded mode and report that once: advice left in
already-loaded classes by removed rules SHALL find no bound rule in the new snapshot and return
immediately (no-op), and selectors added for already-loaded classes SHALL apply only after restart.

#### Scenario: Rule removed without Instrumentation
- **WHEN** a rule for an already-loaded class is removed and `Instrumentation` is unavailable
- **THEN** the class keeps the advice bytecode but no attribute is written any more

#### Scenario: Selector added without Instrumentation
- **WHEN** a selector is added for an already-loaded class and `Instrumentation` is unavailable
- **THEN** a warning states that the change applies to that class only after restart
