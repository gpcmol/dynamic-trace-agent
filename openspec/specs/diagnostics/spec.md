# diagnostics Specification

## Purpose
TBD - created by archiving change rewrite-v2. Update Purpose after archive.
## Requirements
### Requirement: Opt-in debug logging
Debug logging SHALL only be active when OpenTelemetry agent debugging is enabled
(`otel.javaagent.debug=true`). With debugging off, the agent SHALL emit only startup warnings for
rejected configuration (one per problem) and SHALL add no per-call logging cost.

#### Scenario: Debug off
- **WHEN** a selected method is called 1M times
- **THEN** nothing is logged

### Requirement: Lifecycle visibility in debug mode
In debug mode the agent SHALL log: configuration loaded/rejected (with reason), rules and
selectors accepted, rules disabled and why, classes (re)transformed or restored per reload, and
reload timing.

#### Scenario: Reload
- **WHEN** the file changes and debug is on
- **THEN** one log block summarises added/removed/changed rules and retransformed classes

### Requirement: No sensitive values
Logs SHALL NOT contain resolved attribute values.

#### Scenario: Debug on with PII-bearing getters
- **WHEN** enrichment runs in debug mode
- **THEN** log lines contain rule identity and counts only, not values

### Requirement: Log messages cannot be forged
Text taken from the configuration file (keys, paths, conditions, names) SHALL reach the log only
with control characters, line separators and invisible formatting characters escaped, and messages
SHALL be limited in length.

#### Scenario: Line break in a quoted path
- **WHEN** a rejected document has a path containing a line break followed by text that looks like
  a log line
- **THEN** the warning is written as one line with the line break shown as an escape

### Requirement: Explain silent runtime cases in debug mode
In debug mode the agent SHALL report once per rule, attribute and binding when an attribute has no
value (a `null` on the way, a missing property, an index out of range, a `Map` or a failing getter)
or a value of a type that cannot be written, and once per rule and binding when its condition
fails at runtime. The messages SHALL name the rule, `Class#method`, the attribute key and path, and
for an unwritable value its type, never the value. With debug off these cases SHALL stay silent and
SHALL add no logging cost.

#### Scenario: Attribute without value
- **WHEN** debug is on and `$arg0.broken` throws on every call of a method called three times
- **THEN** one debug message names the rule, the method and attribute `broken`, and the other
  attributes are written

#### Scenario: Unwritable value
- **WHEN** debug is on and an attribute path resolves to a `Passenger` object
- **THEN** one debug message names the attribute and the type `Passenger`, not the value

#### Scenario: Failing condition
- **WHEN** debug is on and a condition throws twice
- **THEN** it counts as `false` both times and one debug message is logged

#### Scenario: Debug off
- **WHEN** debug is off and attributes have no value
- **THEN** nothing is logged

