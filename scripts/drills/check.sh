#!/usr/bin/env bash
# LED-21: one snapshot of the Ledger for the outage drills (docs/DRILLS.md). Run it before and after each drill.
# Each line is "name value". "-" means that part is down or paused, which is expected during a drill.
set -u

sql() {
  echo "$1" | docker exec -i devkit-mysql-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N ledger' 2>/dev/null || echo "-"
}

# Sum of the last offsets of every partition of a topic: grows by one per event published.
topic_end() {
  docker exec devkit-kafka-1 kafka-get-offsets --bootstrap-server localhost:9092 --topic "$1" 2>/dev/null \
    | awk -F: '{ s += $3 } END { if (NR) print s; else print "-" }'
}

echo "time $(date -u +%H:%M:%S)"
for port in 8081 8082; do
  echo "health_$port $(curl -s -m 3 -o /dev/null -w '%{http_code}' "localhost:$port/actuator/health")"
done

sql "SELECT CONCAT('movements_', reference_type), COUNT(*) FROM ledger_movement GROUP BY reference_type ORDER BY reference_type;" | tr '\t' ' '
echo "outbox_unsent $(sql 'SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL;')"
echo "outbox_total $(sql 'SELECT COUNT(*) FROM outbox_event;')"
echo "parked $(sql 'SELECT COUNT(*) FROM parked_event;')"
# The three checks from HANDOVER §8 "Is the whole Ledger balanced?": all must be 0.
echo "postings_sum $(sql 'SELECT COALESCE(SUM(amount_cents), 0) FROM ledger_posting;')"
echo "movements_not_zero $(sql 'SELECT COUNT(*) FROM (SELECT movement_id FROM ledger_posting GROUP BY movement_id HAVING SUM(amount_cents) <> 0) x;')"
echo "balances_not_matching $(sql "SELECT COUNT(*) FROM (SELECT a.id FROM ledger_account a LEFT JOIN ledger_posting p ON p.account_id = a.id WHERE a.owner_type = 'STUDENT' GROUP BY a.id, a.balance_cents HAVING a.balance_cents <> COALESCE(SUM(p.amount_cents), 0)) y;")"

echo "ledger_v1_end $(topic_end ledger.v1)"
echo "ledger_dlt_end $(topic_end ledger.dlt)"
lag=$(docker exec devkit-kafka-1 kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group ledger 2>/dev/null \
  | awk '$1 == "ledger" && $6 ~ /^[0-9]+$/ { s += $6; n++ } END { if (n) print s; else print "-" }')
echo "consumer_lag $lag"
