#!/usr/bin/env bash
#
# lcm.sh — life-cycle management: update dependency and build-plugin versions.
#
#   ./lcm.sh --check   show what is outdated; nothing is changed
#   ./lcm.sh           update to the latest versions within the current major version
#   ./lcm.sh --major   update, including major-version jumps (e.g. 5.x -> 6.x)
#
# Every version is a property in the root pom.xml; the versions-maven-plugin (configured there)
# does the work. Docker base images and GitHub Actions are updated by Dependabot
# (.github/dependabot.yml).

set -euo pipefail
cd "$(dirname "$0")"

# Files outside the pom that pin the OpenTelemetry agent version.
AGENT_PINS=(.github/workflows/ci.yml README.md deploy/kubernetes/instrumentation.yaml)

agent_version() {
  mvn -q -N help:evaluate -Dexpression=otel.agent.version -DforceStdout
}

case "${1:-}" in
  --check) exec mvn -B -N versions:display-property-updates -DallowMajorUpdates=false ;;
  --major) major=true ;;
  "") major=false ;;
  *) sed -n '3,7p' "$0" >&2; exit 2 ;;
esac

old_agent="$(agent_version)"
mvn -B -N versions:update-properties -DallowMajorUpdates="$major"
new_agent="$(agent_version)"

if [ "$old_agent" != "$new_agent" ]; then
  OLD="$old_agent" NEW="$new_agent" perl -pi -e 's/\Q$ENV{OLD}\E/$ENV{NEW}/g' "${AGENT_PINS[@]}"
fi

echo "Done. Review the changes with 'git diff', then run: mvn verify"
