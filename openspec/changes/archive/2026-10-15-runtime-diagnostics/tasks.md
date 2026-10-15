## 1. Implementation

- [x] 1.1 `AgentLog.isDebugEnabled()`
- [x] 1.2 `RuleDiagnostics` (no value, unwritable, condition failure; once per rule/attribute)
- [x] 1.3 Wire into `MethodEnrichment` / `ClassBindings`; no change when debug is off

## 2. Tests and docs

- [x] 2.1 `RuleDiagnosticsTest`: once per attribute, no values logged, silent without debug, condition failure
- [x] 2.2 `docs/06-config-xml.md` §5: behaviour per failure case at load, bind and runtime
- [x] 2.3 `mvn verify` and `openspec validate runtime-diagnostics --strict`
