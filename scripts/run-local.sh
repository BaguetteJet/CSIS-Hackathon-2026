#!/usr/bin/env bash
# Runs the service on this laptop (not in Docker) against the dev kit, with the settings in local.env.
#   scripts/run-local.sh          port 8081
#   scripts/run-local.sh 8082     a second instance, in another terminal
set -euo pipefail
cd "$(dirname "$0")/.."
set -a
# shellcheck disable=SC1091
. ./local.env
set +a
export SERVER_PORT="${1:-8081}"
export INSTANCE_ID="local-$SERVER_PORT"
echo "Starting $SERVICE_NAME on http://localhost:$SERVER_PORT (instance $INSTANCE_ID)"
exec ./gradlew bootRun --no-daemon
