#!/usr/bin/env bash
# LED-21: N hold calls of 1 cent for one student, alternating ledger-1 (8081) and ledger-2 (8082), one new order each.
#   scripts/drills/holds.sh 20               the student with the most money available
#   scripts/drills/holds.sh 10 <studentId>   a given student (use this while MySQL is down)
# Prints one line per call (port, HTTP status, seconds, result), then a count of each.
set -u
n="${1:-10}"
student="${2:-}"

uuid() {
  od -An -tx1 -N16 /dev/urandom | tr -d ' \n' \
    | awk '{ print substr($0,1,8) "-" substr($0,9,4) "-4" substr($0,14,3) "-a" substr($0,18,3) "-" substr($0,21,12) }'
}

if [ -z "$student" ]; then
  student=$(echo "SELECT owner_id FROM ledger_account WHERE owner_type = 'STUDENT' AND purpose = 'AVAILABLE' ORDER BY balance_cents DESC LIMIT 1;" \
    | docker exec -i devkit-mysql-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N ledger' 2>/dev/null)
  [ -n "$student" ] || { echo "No student found: is MySQL up? Pass a studentId instead."; exit 1; }
fi
deal=$(uuid)
echo "student $student deal $deal"

calls=$(for i in $(seq 1 "$n"); do
  port=$((8081 + (i + 1) % 2))
  body="{\"orderId\":\"$(uuid)\",\"studentId\":\"$student\",\"dealId\":\"$deal\",\"cutoffAt\":\"2026-11-14T12:00:00Z\",\"amountCents\":1}"
  out=$(curl -s -m 5 -w ' %{http_code} %{time_total}' -H 'Content-Type: application/json' -d "$body" "localhost:$port/internal/ledger/holds")
  code=$(echo "$out" | awk '{ print $(NF-1) }')
  secs=$(echo "$out" | awk '{ print $NF }')
  result=$(echo "$out" | grep -o '"result":"[^"]*"' | cut -d'"' -f4)
  echo "$port $code ${secs}s ${result:--}"
done)
echo "$calls"
echo "$calls" | awk '{ c[$2 " " $4]++ } END { for (k in c) print "count " k " " c[k] }'
