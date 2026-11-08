#!/usr/bin/env bash
# Runs the k6 last-place test in Docker (no k6 to install), against both Orders instances.
#   scripts/last-place.sh <dealId> <tokens.txt> [cap]
set -euo pipefail
cd "$(dirname "$0")/.."
deal="${1:?usage: scripts/last-place.sh <dealId> <tokens.txt> [cap]}"
tokens="${2:?usage: scripts/last-place.sh <dealId> <tokens.txt> [cap]}"
cp "$tokens" scripts/k6/.tokens.txt
trap 'rm -f scripts/k6/.tokens.txt' EXIT
MSYS_NO_PATHCONV=1 docker run --rm --network hackathon \
  -v "$(pwd -W 2>/dev/null || pwd)/scripts/k6:/scripts" \
  -e DEAL_ID="$deal" -e CAP="${3:-50}" -e TOKENS_FILE=/scripts/.tokens.txt -e TARGETS="${TARGETS:-http://orders-1:8080,http://orders-2:8080}" \
  grafana/k6:0.57.0 run --quiet /scripts/last-place.js
