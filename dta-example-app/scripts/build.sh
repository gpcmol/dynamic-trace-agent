#!/usr/bin/env bash
# Builds the garage demo image (dta-example-app/Dockerfile) from the current sources.
#
# Prerequisites: JDK 21, Maven 3.9, docker.
#
# Usage: dta-example-app/scripts/build.sh
#   IMAGE_NAME  image name  (default: dynamic-trace-app)
#   IMAGE_TAG   image tag   (default: latest)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE="${IMAGE_NAME:-dynamic-trace-app}:${IMAGE_TAG:-latest}"

log() { echo "[build] $*" >&2; }

for tool in mvn docker; do
  command -v "$tool" >/dev/null || { echo "missing prerequisite: $tool" >&2; exit 1; }
done

log "packaging dta-example-app"
mvn -B -q -f "$ROOT/pom.xml" -DskipTests -pl dta-example-app -am package

log "building $IMAGE"
docker build --file "$ROOT/dta-example-app/Dockerfile" --tag "$IMAGE" "$ROOT"

log "image built: $IMAGE"
