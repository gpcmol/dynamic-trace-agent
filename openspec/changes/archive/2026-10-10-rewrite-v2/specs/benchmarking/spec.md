## ADDED Requirements

### Requirement: Macro benchmark
A single command SHALL run an end-to-end k6 benchmark against the example app with three modes:
**baseline** (OTel agent only), **disabled** (our agent, no rules), **enabled** (representative
rule set: 5 static + 10 dynamic attributes with a `<when>`), plus a **verify** run that checks the
enrichment attributes in exported telemetry. Results SHALL be written as Markdown including date,
git commit, Java version, k6 version, load parameters and overhead percentages.

#### Scenario: Full run
- **WHEN** the benchmark command runs
- **THEN** a Markdown report with the three throughput/p95/p99 rows and overhead percentages is produced

### Requirement: Micro benchmarks
JMH benchmarks SHALL cover the hot path in isolation: gateway dispatch, path resolution,
condition evaluation and attribute writing; they SHALL be runnable from Maven.

#### Scenario: Regression review
- **WHEN** a change touches the hot path
- **THEN** JMH results before/after can be compared and recorded in `benchmarks/`

### Requirement: Performance budget
The performance budget SHALL be expressed per call, not as one fixed percentage, because overhead
depends on the configuration (number of selected methods, call frequency, number of attributes). Every release
SHALL record: JMH cost per enrichment (ns/op and bytes/op) for the paths "condition false",
"condition true", and "no matching rule"; macro overhead for a **scaling series** (1, 10 and 100
selected methods; 0, 5 and 20 attributes); and SHALL NOT regress beyond measurement noise against
the previous release. The design goal is the best achievable performance (see
`hot-path-performance`).

#### Scenario: Reload under load
- **WHEN** a reload happens during the macro run
- **THEN** reload duration and p99 impact are recorded

### Requirement: Reproducibility
The benchmark SHALL not depend on hard-coded absolute paths or pre-freed ports beyond documented
prerequisites (k6, jq), and SHALL document that same-host results are environment-sensitive.

#### Scenario: Fresh checkout
- **WHEN** a developer follows `docs/11-benchmarking.md`
- **THEN** the benchmark runs without editing scripts
