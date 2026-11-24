#!/usr/bin/env bash
# Deploys the garage demo (deploy/kubernetes/garage.yaml) and its rule file to the current
# Kubernetes context. Run build.sh first.
#
# The image is pushed to the registry and the pod is always restarted, so it runs the image that
# was just pushed (imagePullPolicy: Always). The ConfigMap is generated from the rule file.
#
# Usage: dta-example-app/scripts/deploy.sh
#   IMAGE_NAME   image name           (default: dynamic-trace-app)
#   IMAGE_TAG    image tag            (default: latest)
#   REGISTRY     registry to push to  (default: localhost:5001)
#   CONFIG_FILE  rule file            (default: config/agent-config.xml)
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NAMESPACE="default" # the namespace named in the manifest
LOCAL_IMAGE="${IMAGE_NAME:-dynamic-trace-app}:${IMAGE_TAG:-latest}"
REGISTRY="${REGISTRY:-localhost:5001}"
CONFIG_FILE="${CONFIG_FILE:-$ROOT/config/agent-config.xml}"

if ! docker image inspect "$LOCAL_IMAGE" >/dev/null 2>&1; then
  echo "missing image $LOCAL_IMAGE — run: dta-example-app/scripts/build.sh" >&2
  exit 1
fi

IMAGE="$REGISTRY/$LOCAL_IMAGE"
docker tag "$LOCAL_IMAGE" "$IMAGE"
docker push --quiet "$IMAGE"

kubectl create configmap agent-config --namespace "$NAMESPACE" \
  --from-file=agent-config.xml="$CONFIG_FILE" --dry-run=client --output yaml \
  | kubectl apply --filename -
sed "s#image: .*#image: $IMAGE#" "$ROOT/deploy/kubernetes/garage.yaml" | kubectl apply --filename -
kubectl rollout restart deployment/garage --namespace "$NAMESPACE"
kubectl rollout status deployment/garage --namespace "$NAMESPACE" --timeout=180s

echo "Deployed $IMAGE; check with dta-example-app/scripts/verify.sh"
