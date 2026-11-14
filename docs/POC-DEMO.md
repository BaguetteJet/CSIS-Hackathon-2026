# Proof-of-concept demo

About 8 minutes. Every block is copy-paste, in one terminal, in order, from the dev kit folder. Outputs are what we saw from `checkpoint-2` (Sat 14 Nov, 15:41); IDs and exact balances differ, but the changes match. Evidence: [poc-integrated-2026-11-14.md](evidence/poc-integrated-2026-11-14.md).

## 0 Before you start (once)

| Step | Run | Ready when |
|---|---|---|
| Dev kit up, our Ledger behind the gateway, the dashboard copied | HANDOVER §4 step 2 | http://localhost:8080 shows a page |
| Both instances from the tag | In `line-a-ledger`: `git checkout checkpoint-2`, then `./check` (JDK 21) | `Ready: tests pass and two instances run healthy within 512 MB each.` |
| Grafana | http://localhost:3000/d/ledger/ledger, Scrape job `services` | Panels draw |

If port 9090 is already taken, Prometheus won't start: give it another host port with a compose override (`ports: !override ['127.0.0.1:9091:9090']`). Grafana reaches it inside the network.

Paste the shortcuts once, in the dev kit folder. `DB` is the Ledger's database (`ledger` on a fresh deploy); `PUB` sends contract samples for the steps no `./sim` scenario covers:

```bash
DB=${DB:-ledger}
PUB=${PUB:-../line-a-ledger/scripts/publish-sample.sh}
me()  { curl -s -H "Authorization: Bearer $(./token $1)" localhost:8080/api/ledger/me; echo; }
sql() { docker exec devkit-mysql-1 sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" $DB -N -e \"$1\"" 2>/dev/null; }
uuid() { uuidgen | tr A-Z a-z; }
curl -s localhost:8081/actuator/health; echo; curl -s localhost:8082/actuator/health; echo
```

Expect `{"status":"UP",...}` twice.

## 1 The gateway reaches our two instances

```bash
curl -s -H "Authorization: Bearer $(./token student011)" localhost:8080/api/ledger/hello; echo
```

Expect `"service":"ledger","instance":"ledger-1"` (or `ledger-2`).
**Say:** two instances, one database; the database decides, never the instance.

## 2 Top-ups land once

```bash
me student011
./sim money-in --students 204 > /dev/null; sleep 8
me student011
```

Expect `availableCents` up by 2000 (0 → 2000).

## 3 One hold, asked three times on both instances, moves money once

```bash
S=$(me student011 | grep -o '"studentId":"[^"]*"' | cut -d'"' -f4)
B="{\"orderId\":\"$(uuid)\",\"studentId\":\"$S\",\"dealId\":\"$(uuid)\",\"cutoffAt\":\"2026-11-15T12:00:00Z\",\"amountCents\":600}"
for p in 8081 8082 8081; do curl -s -X POST localhost:$p/internal/ledger/holds -H 'Content-Type: application/json' -d "$B"; echo; done
me student011
```

Expect `PLACED` three times, then 600 less available and 600 held (not 1800).
**Say:** the first answer per orderId is stored with the money in one transaction (decision 0003); a retry on either instance reads it back.

## 4 Short of money, and bad input

```bash
curl -s -X POST localhost:8082/internal/ledger/holds -H 'Content-Type: application/json' -d "{\"orderId\":\"$(uuid)\",\"studentId\":\"$S\",\"dealId\":\"$(uuid)\",\"cutoffAt\":\"2026-11-15T12:00:00Z\",\"amountCents\":999999}"; echo
curl -s -X POST localhost:8081/internal/ledger/holds -H 'Content-Type: application/json' -d '{"orderId":"nope","amountCents":0}'; echo
```

Expect `INSUFFICIENT_FUNDS` with `availableCents`, then a 422 `VALIDATION_FAILED` listing all five fields.

## 5 The golden path: 12 students join, the deal tips, the Ledger settles once

```bash
D=$(./sim deal-opens --cutoff 2 | grep -o 'Deal: [0-9a-f-]*' | cut -d' ' -f2); sleep 3
for i in $(seq 31 42); do curl -s -X POST -H "Authorization: Bearer $(./token student0$i)" -H "Idempotency-Key: $(uuid)" localhost:8080/api/orders/deals/$D/orders | grep -o '"status":"[A-Z]*","heldCents":[0-9]*'; done
./clock advance 3m; sleep 15
sql "SELECT charged_count, total_charged_cents, total_commission_cents FROM deal_settlement WHERE deal_id='$D'"
```

Expect 12 × `"status":"CONFIRMED","heldCents":600`, then `12  6000  600`: each order paid 500, 50 to the Union, 100 back.
**Say:** the whole deal settles in one local transaction; a crash charges nothing and redelivery settles once (decision 0007, `SettlementTest`).

## 6 A hold after the deal closed is refused (marked property)

```bash
curl -s -X POST localhost:8081/internal/ledger/holds -H 'Content-Type: application/json' -d "{\"orderId\":\"$(uuid)\",\"studentId\":\"$S\",\"dealId\":\"$D\",\"cutoffAt\":\"2026-11-15T12:00:00Z\",\"amountCents\":600}"; echo
```

Expect `"result":"DEAL_CLOSED"`.

## 7 The statement adds up to the balance

```bash
curl -s -H "Authorization: Bearer $(./token student031)" localhost:8080/api/ledger/me/entries; echo
me student031
```

Expect three entries, newest first: `RETURNED` +100 "Paid €5.00, €1.00 back in your Account", `HOLD` −600 "Set aside for your order", `TOP_UP` +2000. They sum to `availableCents` (1500).

## 8 Refund: back to the student, never more than charged (marked property)

```bash
O=$(sql "SELECT order_id FROM order_charge WHERE deal_id='$D' LIMIT 1"); RS=$(sql "SELECT student_id FROM order_charge WHERE order_id='$O'")
$PUB support.v1 RefundApproved $RS "{\"refundId\":\"$(uuid)\",\"orderId\":\"$O\",\"dealId\":\"$D\",\"studentId\":\"$RS\",\"amountCents\":200}"
$PUB support.v1 RefundApproved $RS "{\"refundId\":\"$(uuid)\",\"orderId\":\"$O\",\"dealId\":\"$D\",\"studentId\":\"$RS\",\"amountCents\":600}"
sleep 8; sql "SELECT amount_cents, commission_reversed_cents, outcome FROM refund WHERE order_id='$O' ORDER BY decided_at"
```

Expect `200  20  POSTED` (20 back from the Union's commission, 180 from the restaurant), then `600  0  EXCEEDS_CHARGE` (only 300 was left of the 500 charge).

## 9 Desk balance for Union finance

```bash
curl -s -H "Authorization: Bearer $(./token union-finance)" localhost:8080/api/ledger/students/$S; echo
me student011
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $(./token student011)" localhost:8080/api/ledger/students/$S
```

Expect the same numbers twice, then `403` for a student.

## 10 Cash-out: set aside first, then paid out

```bash
W=$(uuid); F=$(uuid)
$PUB payments.v1 WithdrawalRequested $S "{\"withdrawalId\":\"$W\",\"studentId\":\"$S\",\"amountCents\":500,\"staffId\":\"$F\"}"; sleep 8; me student011
$PUB payments.v1 WithdrawalCompleted $S "{\"withdrawalId\":\"$W\",\"studentId\":\"$S\",\"amountCents\":500,\"staffId\":\"$F\"}"; sleep 8; me student011
sql "SELECT state FROM withdrawal WHERE withdrawal_id='$W'"
```

Expect 1400/600 → 900/1100 (reserved) → 900/600 (paid out), then `PAID_OUT`.
**Say:** paid out or returned, never both: the first ending wins, like a hold (decision 0008).

## 11 The books balance (marked property)

```bash
sql "SELECT 'all postings', COALESCE(SUM(amount_cents),0) FROM ledger_posting
UNION ALL SELECT 'movements not summing to 0', COUNT(*) FROM (SELECT movement_id FROM ledger_posting GROUP BY movement_id HAVING SUM(amount_cents)<>0) x
UNION ALL SELECT 'student balances not matching postings', COUNT(*) FROM (SELECT a.id FROM ledger_account a LEFT JOIN ledger_posting p ON p.account_id=a.id WHERE a.owner_type='STUDENT' GROUP BY a.id,a.balance_cents HAVING a.balance_cents<>COALESCE(SUM(p.amount_cents),0)) y"
```

Expect `0`, `0`, `0`.
**Say:** every movement is postings that sum to zero, so this one check covers everything above (decision 0002).

## 12 The dashboard

Send some traffic first (the per-second panels show the last minute only), then switch to the Grafana tab:

```bash
for i in $(seq 1 30); do curl -s -o /dev/null -X POST localhost:$((8081 + i % 2))/internal/ledger/holds -H 'Content-Type: application/json' -d "{\"orderId\":\"$(uuid)\",\"studentId\":\"$S\",\"dealId\":\"$(uuid)\",\"cutoffAt\":\"2026-11-15T12:00:00Z\",\"amountCents\":10}"; done
```

Show: holds per second by result and by instance (both instances serve), hold latency well under 2 s, settlements, SettlementIssue 0.

## 13 Always last

```bash
./clock reset
```

## If asked what's not done

LED-14 (waking early refunds), LED-20 (the zero-sum property test), LED-21 (outage drills), the rest of 23 and 24. The status of record is [PHASES.md](../PHASES.md).

In zsh, split a two-column `sql` result with `cut -f1` and `cut -f2`; `set --` doesn't split it.
