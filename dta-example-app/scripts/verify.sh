#!/usr/bin/env bash
# Checks that the deployed garage demo is instrumented: sends one POST /cars and looks in the pod
# log for its span, which must have every attribute key of the rule file. Run deploy.sh first.
# The agent only logs spans when the Instrumentation sets OTEL_JAVAAGENT_DEBUG=true.
#
# Usage: dta-example-app/scripts/verify.sh
#   CONFIG_FILE  rule file that was deployed  (default: config/agent-config.xml)
#   LOCAL_PORT   local port for the request   (default: 18080)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
CONFIG_FILE="${CONFIG_FILE:-$ROOT/config/agent-config.xml}"
LOCAL_PORT="${LOCAL_PORT:-18080}"

garage() { kubectl "$1" --namespace default deployment/garage "${@:2}"; }
fail() { echo "FAIL: $*" >&2; exit 1; }

# 1. The Operator injected the extension.
kubectl get pods --namespace default --selector app=garage \
  --output jsonpath='{range .items[*].spec.initContainers[*]}{.name}{" "}{.image}{"\n"}{end}' \
  | grep extension || fail "no extension is injected"

# 2. The extension loaded the rule file.
garage logs --container garage | grep -F '[dynamic-trace-agent] loaded configuration' | tail -n 1 \
  || fail "the extension did not load a rule file"

# 3. A request that matches the reference rule gives a span with all attributes.
kubectl port-forward --namespace default deployment/garage "$LOCAL_PORT:8080" >/dev/null &
trap 'kill $!' EXIT
TRACE_ID="$(openssl rand -hex 16)"
curl -sf -o /dev/null --retry 10 --retry-connrefused --retry-delay 1 \
  -X POST "http://127.0.0.1:$LOCAL_PORT/cars" \
  -H 'Content-Type: application/json' -H "traceparent: 00-$TRACE_ID-$(openssl rand -hex 8)-01" \
  -d '{"brand":"bmw","model":"x5","year":2020,"color":"black","licensePlate":"AB-123-C","vin":"WBA1",
       "mileage":10000,"fuelType":"petrol","transmission":"automatic","passengers":[{"name":"Jan"},{"name":"Piet"}]}' \
  || fail "POST /cars failed"
sleep 2
SPAN="$(garage logs --container garage --since=1m | grep -F " $TRACE_ID ")" \
  || fail "no span of trace $TRACE_ID in the pod log (OTEL_JAVAAGENT_DEBUG=true?)"

for key in $(grep -o 'key="[^"]*"' "$CONFIG_FILE" | cut -d'"' -f2); do
  grep -q "[{ ]$key=" <<<"$SPAN" || fail "the span has no attribute '$key'"
done
echo "PASS: the span has all attributes of $CONFIG_FILE"
