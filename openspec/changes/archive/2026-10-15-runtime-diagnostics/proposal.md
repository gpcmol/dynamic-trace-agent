## Why

Runtime problems are deliberately silent so the application is never affected: an attribute whose
path resolves to `null`, a value that cannot be written, a condition that throws. That makes "why is
my attribute missing?" hard to answer. Debug mode should explain it without flooding the log or
costing anything when debug is off.

## What Changes

- In debug mode the agent reports, once per (rule, attribute) and binding: an attribute without a
  value, an attribute whose value type cannot be written (type name only), and once per rule a
  condition that failed at runtime.
- With debug off nothing changes: no logging, one `null` check per rule on the hot path.
- `docs/06-config-xml.md` §5 lists what the agent does in every failure case (load, bind,
  runtime): rejected, ignored, `null`/skipped, and which message.

## Capabilities

### New Capabilities

_None._

### Modified Capabilities

- `diagnostics`: explanations of silent runtime cases in debug mode.

## Impact

- `dta-extension` `engine`: new `RuleDiagnostics`, `MethodEnrichment`/`ClassBindings` wiring;
  `diag.AgentLog.isDebugEnabled()`.
- Tests: `RuleDiagnosticsTest`.
- Docs: `docs/06-config-xml.md` §5.
