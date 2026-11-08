#!/usr/bin/env bash
# For the organisers, when the starter is copied into a team's repository:
#   scripts/set-service-name.sh orders
# Sets SERVICE_NAME, and the database name and user, in .env, service.env and local.env.
set -euo pipefail
cd "$(dirname "$0")/.."
name="${1:-}"
case "$name" in
  deals|orders|ledger|payments|restaurant-ops|support|experience|platform) ;;
  *) echo "usage: $0 deals|orders|ledger|payments|restaurant-ops|support|experience|platform"; exit 2 ;;
esac
old=$(sed -n 's/^SERVICE_NAME=//p' .env)
for f in .env service.env local.env; do
  sed -i.bak -e "s/^SERVICE_NAME=.*/SERVICE_NAME=$name/" \
             -e "s#3306/$old\$#3306/$name#" -e "s/^DB_USER=.*/DB_USER=$name/" -e "s/^DB_PASSWORD=.*/DB_PASSWORD=$name/" "$f"
  rm -f "$f.bak"
done
if [ "$name" = orders ]; then
  sed -i.bak -e 's#^HOLD_CLIENT=stub#HOLD_CLIENT=http\nLEDGER_URL=http://ledger:8080#' service.env && rm -f service.env.bak
fi
echo "SERVICE_NAME is now $name:"; grep -H "SERVICE_NAME\|DB_\|LEDGER_URL\|HOLD_CLIENT" .env service.env local.env
