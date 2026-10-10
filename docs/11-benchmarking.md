# 11 — Benchmarking

Two levels, both in `dta-benchmarks` (spec `benchmarking`). Results are committed to `benchmarks/`
so every release can be compared with the previous one.

---

## 1. Micro benchmarks (JMH)

```bash
mvn -DskipTests package
java -jar dta-benchmarks/target/benchmarks.jar "EngineBenchmark|PathAndConditionBenchmark" -prof gc
```

| Benchmark | Measures |
|---|---|
| `EngineBenchmark.conditionFalse` | exit whose condition is false — must stay at **0 B/op** |
| `EngineBenchmark.noMatchingRule` | instrumented method without rule |
| `EngineBenchmark.conditionTrue` (`attributes` = 0 / 5 / 20) | full enrichment of a recording span |
| `PathAndConditionBenchmark.*` | path reads and the reference condition in isolation |

Read `ns/op` and `gc.alloc.rate.norm` (bytes per call). Compare against the previous file in
`benchmarks/jmh-*.md`; a regression beyond the error bars needs an explanation in the change.

---

## 2. Macro benchmark (k6)

Prerequisites: JDK 21 (with `jcmd`), [k6](https://k6.io), `jq`, `curl`.

```bash
mvn -DskipTests package
dta-benchmarks/scripts/bench.sh                      # 1M requests, 50 VUs, all modes
dta-benchmarks/scripts/bench.sh --reload-every 2     # + live reload every 2 s during 'enabled'
dta-benchmarks/scripts/bench.sh --match-ratio 0.2    # 80 % of requests take the condition-false path
dta-benchmarks/scripts/bench.sh --measurement 100000 --modes "disabled enabled"
```

| Mode | JVM setup | Shows |
|---|---|---|
| `baseline` | OTel agent only | the cost of OpenTelemetry itself |
| `disabled` | + extension, no configuration file | the cost of loading the extension |
| `enabled` | + `config/agent-config.xml` | the cost of enrichment |
| `verify` | `enabled`, exporting to the OTLP stub | that the attributes are really written (PASS/FAIL) |

The report (`benchmarks/results/<timestamp>.md`) contains startup time, throughput, p95/p99, the
overheads, the verification result and — for `enabled` — heap after GC and loaded classes before and
after the measurement (with reloads), as a leak check under load. Raw k6 output and application logs
stay in `dta-benchmarks/target/bench-<timestamp>/`.

---

## 3. Reading results

- Load generator and application share one host: expect ±1.5 percentage points between runs. Run
  three times and use the median; compare only runs from the same host.
- "enabled vs disabled" is the cost of enrichment itself; "enabled vs baseline" is the full cost of
  the extension on top of OpenTelemetry.
- The `verify` mode exports spans and is slower by design; do not compare its throughput.
- The demo application keeps every parked car in memory; the script empties it before measuring
  heap.

---

## 4. Not covered yet

- A macro scaling series over the number of selected methods (1 / 10 / 100). Per call, only the
  methods that are actually called cost anything; class-load and retransformation cost grow with the
  plan and should be measured with a generated application (task 14.5).
