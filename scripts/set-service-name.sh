#!/usr/bin/env bash
# For the organisers, when the starter is copied into a team's repository:
#   scripts/set-service-name.sh orders
# Sets SERVICE_NAME in the example and local .env, and updates the database settings.
# For orders it also sets the hold call to the dev kit's fake Ledger, so orders reach Paid:
#   service.env  HOLD_CLIENT=http  LEDGER_URL=http://fakes:8080      (the two containers)
#   local.env    HOLD_CLIENT=http  LEDGER_URL=http://localhost:8091  (IntelliJ; the fakes' laptop-only port)
# (The dev kit's hold stub, http://hold-stub:8080, is for testing each answer: dev kit README.)
set -euo pipefail
usage="usage: $0 deals|orders|ledger|payments|restaurant-ops|support|experience|platform"
case "${1:-}" in -h|--help) echo "$usage"; exit 0 ;; esac
cd "$(dirname "$0")/.."
name="${1:-}"
case "$name" in
  deals|orders|ledger|payments|restaurant-ops|support|experience|platform) ;;
  *) echo "$usage"; exit 2 ;;
esac
old=$(sed -n 's/^SERVICE_NAME=//p' .env.example)
files=(.env.example service.env local.env)
if [[ -f .env ]]; then
  files+=(.env)
fi
for f in "${files[@]}"; do
  sed -i.bak -e "s/^SERVICE_NAME=.*/SERVICE_NAME=$name/" \
             -e "s#3306/$old\$#3306/$name#" -e "s/^DB_USER=.*/DB_USER=$name/" -e "s/^DB_PASSWORD=.*/DB_PASSWORD=$name/" "$f"
  rm -f "$f.bak"
done
# Orders: HOLD_CLIENT=http and the fake Ledger's address (awk, not sed: BSD sed has no \n in a replacement).
set_hold() {   # set_hold <file> <ledger-url>
  awk -v url="$2" '/^HOLD_CLIENT=/ { next } /^LEDGER_URL=/ { print "HOLD_CLIENT=http"; print "LEDGER_URL=" url; next } { print }' "$1" > "$1.tmp" && mv "$1.tmp" "$1"
}
if [ "$name" = orders ]; then
  set_hold service.env http://fakes:8080
  set_hold local.env http://localhost:8091
fi
printf 'SERVICE_NAME is now %s\n' "$name"
