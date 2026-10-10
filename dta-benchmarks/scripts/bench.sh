#!/usr/bin/env bash
# Macro benchmark for the dynamic trace agent (spec "benchmarking", docs/11-benchmarking.md).
#
# Runs the garage demo under k6 load in these modes, one JVM per mode:
#   baseline  OpenTelemetry agent only (no extension)          → cost of OTel itself
#   disabled  OTel agent + extension, no configuration file     → cost of loading the extension
#   enabled   OTel agent + extension + config/agent-config.xml  → cost of enrichment
#   verify    like "enabled", exporting spans to the OTLP stub, then checks the attributes
#
# Overheads are reported against "baseline" (the extension's full cost on top of OpenTelemetry) and
# against "disabled" (the cost of enrichment itself). Results: benchmarks/results/<timestamp>.md
#
# Prerequisites: JDK 21, k6, jq, curl; run `mvn -DskipTests package` first.
#
# Usage: dta-benchmarks/scripts/bench.sh [--measurement N] [--warmup N] [--vus N]
#          [--modes "baseline disabled enabled verify"] [--match-ratio R] [--reload-every SECONDS]
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
AGENT_JAR="$ROOT/dta-benchmarks/target/agent/opentelemetry-javaagent.jar"
EXTENSION_JAR="$ROOT/dta-extension/target/dynamic-trace-agent.jar"
APP_JAR="$ROOT/dta-example-app/target/garage.jar"
REFERENCE_CONFIG="$ROOT/config/agent-config.xml"
K6_SCRIPT="$ROOT/dta-benchmarks/scripts/bench.js"

MEASUREMENT=1000000
WARMUP=10000
VUS=50
MODES="baseline disabled enabled verify"
MATCH_RATIO=1.0
RELOAD_EVERY=0
APP_PORT="${APP_PORT:-18090}"
STUB_PORT="${STUB_PORT:-14390}"

while [ $# -gt 0 ]; do
  case "$1" in
    --measurement) MEASUREMENT="$2"; shift 2 ;;
    --warmup) WARMUP="$2"; shift 2 ;;
    --vus) VUS="$2"; shift 2 ;;
    --modes) MODES="$2"; shift 2 ;;
    --match-ratio) MATCH_RATIO="$2"; shift 2 ;;
    --reload-every) RELOAD_EVERY="$2"; shift 2 ;;
    *) echo "unknown argument: $1" >&2; exit 1 ;;
  esac
done

for tool in java jcmd k6 jq curl; do
  command -v "$tool" >/dev/null || { echo "missing prerequisite: $tool" >&2; exit 1; }
done
for jar in "$AGENT_JAR" "$EXTENSION_JAR" "$APP_JAR"; do
  [ -f "$jar" ] || { echo "missing $jar — run: mvn -DskipTests package" >&2; exit 1; }
done

STAMP="$(date -u +%Y-%m-%dT%H-%M-%SZ)"
WORK="$ROOT/dta-benchmarks/target/bench-$STAMP"
RESULTS_DIR="$ROOT/benchmarks/results"
REPORT="$RESULTS_DIR/$STAMP.md"
mkdir -p "$WORK/extensions" "$RESULTS_DIR"
cp "$EXTENSION_JAR" "$WORK/extensions/"
APP_URL="http://127.0.0.1:$APP_PORT/cars"

log() { echo "[bench] $*" >&2; }

now_ms() { # portable millisecond clock (BSD date has no %N)
  python3 -c 'import time; print(int(time.time() * 1000))'
}

APP_PID=""
STUB_PID=""
RELOADER_PID=""
cleanup() {
  for pid in "$RELOADER_PID" "$APP_PID" "$STUB_PID"; do
    if [ -n "$pid" ]; then kill "$pid" 2>/dev/null || true; wait "$pid" 2>/dev/null || true; fi
  done
}
trap cleanup EXIT

wait_until_up() { # <url> <pid> <timeout-seconds>
  local deadline=$(( $(date +%s) + $3 ))
  while [ "$(date +%s)" -lt "$deadline" ]; do
    kill -0 "$2" 2>/dev/null || return 1
    curl -s -o /dev/null --max-time 1 "$1" && return 0
    sleep 0.1
  done
  return 1
}

start_stub() {
  OTLP_STUB_PORT="$STUB_PORT" OTLP_STUB_FILE="$WORK/telemetry.log" \
    java -cp "$APP_JAR" otel.OtlpStub >"$WORK/stub.log" 2>&1 &
  STUB_PID=$!
  wait_until_up "http://127.0.0.1:$STUB_PORT/" "$STUB_PID" 30 || { log "stub did not start"; exit 1; }
}

# start_app <mode>; sets APP_PID and STARTUP_MS (not run in a subshell, so APP_PID survives)
start_app() {
  local mode="$1" exporter="none" extensions="" config=""
  case "$mode" in
    baseline) ;;
    disabled) extensions="$WORK/extensions" ;;
    enabled) extensions="$WORK/extensions"; config="$WORK/agent-config.xml" ;;
    verify) extensions="$WORK/extensions"; config="$WORK/agent-config.xml"; exporter="otlp" ;;
  esac
  cp "$REFERENCE_CONFIG" "$WORK/agent-config.xml"
  local start; start=$(now_ms)
  env JAVA_TOOL_OPTIONS="-javaagent:$AGENT_JAR" \
      OTEL_JAVAAGENT_EXTENSIONS="$extensions" \
      DYNAMIC_TRACE_CONFIG_FILE="$config" \
      DYNAMIC_TRACE_CONFIG_RELOAD_INTERVAL=1 \
      OTEL_SERVICE_NAME=garage-bench \
      OTEL_TRACES_EXPORTER="$exporter" OTEL_METRICS_EXPORTER=none OTEL_LOGS_EXPORTER=none \
      OTEL_EXPORTER_OTLP_PROTOCOL=http/protobuf \
      OTEL_EXPORTER_OTLP_ENDPOINT="http://127.0.0.1:$STUB_PORT" \
      PORT="$APP_PORT" \
      java -jar "$APP_JAR" >"$WORK/app-$mode.log" 2>&1 &
  APP_PID=$!
  wait_until_up "$APP_URL" "$APP_PID" 60 || { log "app ($mode) did not start; see $WORK/app-$mode.log"; exit 1; }
  STARTUP_MS=$(( $(now_ms) - start ))
}

stop_app() {
  kill "$APP_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
  APP_PID=""
}

# Rewrites the configuration every N seconds (alternating an extra attribute) during the run.
start_reloader() {
  [ "$RELOAD_EVERY" -gt 0 ] || return 0
  (
    flip=0
    while true; do
      sleep "$RELOAD_EVERY"
      if [ "$flip" -eq 0 ]; then
        sed 's#</rule>#<attribute key="reloaded" path="$arg0.brand"/></rule>#' "$REFERENCE_CONFIG" >"$WORK/agent-config.xml.tmp"
        flip=1
      else
        cp "$REFERENCE_CONFIG" "$WORK/agent-config.xml.tmp"; flip=0
      fi
      mv "$WORK/agent-config.xml.tmp" "$WORK/agent-config.xml"
    done
  ) &
  RELOADER_PID=$!
}

stop_reloader() {
  [ -n "$RELOADER_PID" ] || return 0
  kill "$RELOADER_PID" 2>/dev/null || true; wait "$RELOADER_PID" 2>/dev/null || true; RELOADER_PID=""
}

run_k6() { # <mode> <phase> <iterations>
  local summary="$WORK/k6-$1-$2.json"
  k6 run "$K6_SCRIPT" --quiet \
    --env BASE_URL="$APP_URL" --env ITERATIONS="$3" --env VUS="$VUS" --env MATCH_RATIO="$MATCH_RATIO" \
    --summary-export="$summary" >"$WORK/k6-$1-$2.log" 2>&1 || { log "k6 failed; see $WORK/k6-$1-$2.log"; exit 1; }
  echo "$summary"
}

metric() { jq -r "$2 // 0" "$1"; }

# Heap used after a full GC (KB) and loaded classes of the running app.
heap_after_gc_kb() {
  jcmd "$APP_PID" GC.run >/dev/null 2>&1 || true
  jcmd "$APP_PID" GC.heap_info 2>/dev/null | awk '/garbage-first heap|PSYoungGen|def new generation|total .* used/ { for (i = 1; i <= NF; i++) if ($i == "used") { gsub(/K,?/, "", $(i + 1)); print $(i + 1); exit } }'
}
loaded_classes() {
  jcmd "$APP_PID" VM.classloader_stats 2>/dev/null | awk '/^Total/ { print $4; exit }'
}

declare -A THROUGHPUT P95 P99 FAILED STARTUP
HEAP_BEFORE=""; HEAP_AFTER=""; CLASSES_BEFORE=""; CLASSES_AFTER=""
run_mode() {
  local mode="$1" iterations="$MEASUREMENT"
  [ "$mode" = verify ] && iterations=1000
  log "mode $mode: starting"
  start_app "$mode"
  STARTUP[$mode]=$STARTUP_MS
  curl -s -o /dev/null -X DELETE "$APP_URL" || true
  run_k6 "$mode" warmup "$WARMUP" >/dev/null
  curl -s -o /dev/null -X DELETE "$APP_URL" || true
  if [ "$mode" = enabled ]; then
    HEAP_BEFORE=$(heap_after_gc_kb); CLASSES_BEFORE=$(loaded_classes)
    start_reloader
  fi
  log "mode $mode: measuring $iterations requests"
  local summary; summary=$(run_k6 "$mode" measurement "$iterations")
  stop_reloader
  if [ "$mode" = enabled ]; then
    # The demo keeps every parked car; empty it so only agent memory remains.
    curl -s -o /dev/null -X DELETE "$APP_URL" || true
    HEAP_AFTER=$(heap_after_gc_kb); CLASSES_AFTER=$(loaded_classes)
  fi
  THROUGHPUT[$mode]=$(metric "$summary" '.metrics.http_reqs.rate')
  P95[$mode]=$(metric "$summary" '.metrics.http_req_duration."p(95)"')
  P99[$mode]=$(metric "$summary" '.metrics.http_req_duration."p(99)"')
  FAILED[$mode]=$(metric "$summary" '.metrics.http_req_failed.value')
  stop_app
}

overhead() { awk -v a="$1" -v b="$2" 'BEGIN { if (b == 0) print "n/a"; else printf "%.1f %%", (b - a) / b * 100 }'; }

verify_attributes() {
  sleep 2
  local missing=""
  for key in debug domain team environment region service brand model mileage passengers accepted status; do
    grep -q "key: \"$key\"" "$WORK/telemetry.log" 2>/dev/null || missing="$missing $key"
  done
  if [ -z "$missing" ]; then echo "PASS"; else echo "FAIL (missing:$missing)"; fi
}

start_stub
VERIFY_RESULT="not run"
for mode in $MODES; do
  run_mode "$mode"
  [ "$mode" = verify ] && VERIFY_RESULT=$(verify_attributes)
done
RELOADS=$(grep -c "loaded configuration" "$WORK/app-enabled.log" 2>/dev/null || echo 0)

{
  echo "# Macro benchmark $STAMP"
  echo
  echo "| | |"
  echo "|---|---|"
  echo "| Commit | $(git -C "$ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown) |"
  echo "| Java | $(java -version 2>&1 | head -1) |"
  echo "| k6 | $(k6 version 2>/dev/null | head -1) |"
  echo "| OS / CPU | $(uname -sm) / $(getconf _NPROCESSORS_ONLN) cores |"
  echo "| Load | $MEASUREMENT requests, $VUS VUs, warm-up $WARMUP, match ratio $MATCH_RATIO |"
  echo "| Reloads during 'enabled' | every ${RELOAD_EVERY}s → $((RELOADS > 0 ? RELOADS - 1 : 0)) reloads |"
  echo
  echo "| mode | startup (ms) | throughput (req/s) | p95 (ms) | p99 (ms) | failed |"
  echo "|---|---:|---:|---:|---:|---:|"
  for mode in $MODES; do
    printf '| %s | %s | %.0f | %.2f | %.2f | %s |\n' "$mode" "${STARTUP[$mode]}" "${THROUGHPUT[$mode]}" "${P95[$mode]}" "${P99[$mode]}" "${FAILED[$mode]}"
  done
  echo
  if [ -n "${THROUGHPUT[enabled]:-}" ] && [ -n "${THROUGHPUT[baseline]:-}" ]; then
    echo "- Overhead enabled vs baseline (OTel only): **$(overhead "${THROUGHPUT[enabled]}" "${THROUGHPUT[baseline]}")**"
  fi
  if [ -n "${THROUGHPUT[enabled]:-}" ] && [ -n "${THROUGHPUT[disabled]:-}" ]; then
    echo "- Overhead enabled vs disabled (cost of enrichment): **$(overhead "${THROUGHPUT[enabled]}" "${THROUGHPUT[disabled]}")**"
  fi
  if [ -n "${THROUGHPUT[disabled]:-}" ] && [ -n "${THROUGHPUT[baseline]:-}" ]; then
    echo "- Overhead disabled vs baseline (extension loaded, no rules): $(overhead "${THROUGHPUT[disabled]}" "${THROUGHPUT[baseline]}")"
  fi
  echo "- Verification of exported attributes: **$VERIFY_RESULT**"
  if [ -n "$HEAP_BEFORE" ]; then
    echo "- 'enabled' memory, before → after the measurement with reloads: heap after GC"
    echo "  ${HEAP_BEFORE} KB → ${HEAP_AFTER} KB, loaded classes ${CLASSES_BEFORE} → ${CLASSES_AFTER}"
  fi
  echo
  echo "Load generator and application share one host: results depend on CPU scheduling, JIT"
  echo "warm-up and host load. Repeat on the target environment before drawing conclusions."
} >"$REPORT"

cat "$REPORT"
log "report: $REPORT (raw data: $WORK)"
