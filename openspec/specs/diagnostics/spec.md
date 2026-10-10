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

