# Ledger Handover

## 1. What it is

The Ledger is the bank inside Campus Group-Buy. It answers one question, "can this student pay?", and it records every cent that moves. Money never appears or disappears: every movement is two or more postings that add up to **zero**.

- Students' money goes in by top-up, is set aside for an order (**hold**), is charged when the deal confirms, and comes back if the order or deal falls through.
- It runs as two identical instances, `ledger-1` and `ledger-2`, on ports **8081** and **8082**, sharing one MySQL database. Either can serve any request.

## 2. Before you start

You need the reference machine with:

1. Git and Docker with Compose
2. The **dev kit** cloned and started
3. A `cs4297.skynet.ie` (Forgejo) login

## 3. Deploy

Work in the folder that contains both `dev-kit/` and `line-a-ledger/`.

**Step 1: get the code**

```bash
git clone https://cs4297.skynet.ie/hackathon-2026/line-a-ledger.git
cp line-a-ledger/.env.example line-a-ledger/.env
```

**Step 2: tell the dev kit to use the real Ledger, and start it**

```bash
cd dev-kit
sed -i 's|^LEDGER_URL=.*|LEDGER_URL=http://ledger:8080|; s|^FAKE_EXCLUDE=.*|FAKE_EXCLUDE=ledger|' .env
cp ../line-a-ledger/grafana/ledger.json grafana/dashboards/
mkdir -p grafana/provisioning/alerting && cp ../line-a-ledger/grafana/alerting/ledger.yml grafana/provisioning/alerting/
docker compose --profile full up -d
```

If the dev kit was already running, also run `docker compose up -d --force-recreate grafana`

Ready when http://localhost:8080 loads, about a minute.

**Step 3: start the two Ledger instances**

```bash
cd ../line-a-ledger
IMAGE=cs4297.skynet.ie/hackathon-2026/line-a-ledger:release docker compose up -d --no-build
```

**Step 4: both instances healthy**

```bash
curl -s localhost:8081/actuator/health; echo; curl -s localhost:8082/actuator/health; echo
```

Expected, twice: `{"status":"UP","groups":["liveness","readiness"]}`

A first start builds the database schema by itself (Flyway). A restart keeps every balance. Nothing to configure: all settings live in `service.env`, already set for the dev kit.

## 4. Check it works

Run the shell commands from `dev-kit/`. They drive the fake services, Orders and Deals, so the Ledger sees real traffic. Your IDs and amounts will differ. The shapes and the zeros should match.

Open the database shell once, and keep it open for checks 4 to 8:

```bash
docker exec -it devkit-mysql-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" ledger'
```

Where a query says `<studentId>` or `<dealId>`, paste the real value.

### Check 1: the gateway reaches the Ledger

```bash
curl -s -H "Authorization: Bearer $(./token student011)" localhost:8080/api/ledger/hello; echo
```

Expect `"service":"ledger"`, and `"instance":"ledger-1"` or `"ledger-2"`.

### Check 2: a student gets money

Every student starts at 0, so a top-up comes first.

```bash
./sim money-in --students 204
curl -s -H "Authorization: Bearer $(./token student011)" localhost:8080/api/ledger/me; echo
```

Expect lines of `payments.v1  TopUpRecorded` from the sim, then `availableCents` 2000 more than before (we saw 5 → 2005). If it hasn't moved, wait 10 s and run the `curl` again.

### Check 3: one hold, asked three times, moves money once

```bash
S=$(curl -s -H "Authorization: Bearer $(./token student011)" localhost:8080/api/ledger/me | grep -o '"studentId":"[^"]*"' | cut -d'"' -f4)
B="{\"orderId\":\"$(cat /proc/sys/kernel/random/uuid)\",\"studentId\":\"$S\",\"dealId\":\"$(cat /proc/sys/kernel/random/uuid)\",\"cutoffAt\":\"2026-11-15T12:00:00Z\",\"amountCents\":600}"
for p in 8081 8082 8081; do curl -s -X POST localhost:$p/internal/ledger/holds -H 'Content-Type: application/json' -d "$B"; echo; done
```

Expect `"result":"PLACED"` **3 times**. `/me` now shows 600 less available and 600 held, not 1800 less.


### Check 4: recompute one student's balance from the tables

Check student ID

```bash
echo $S
```

In the **SQL console**, with `$S` from check 3 as `<studentId>`:

```sql
SELECT a.purpose, a.balance_cents AS kept, COALESCE(SUM(p.amount_cents), 0) AS from_postings
FROM ledger_account a LEFT JOIN ledger_posting p ON p.account_id = a.id
WHERE a.owner_type = 'STUDENT' AND a.owner_id = '<studentId>'
GROUP BY a.id, a.purpose, a.balance_cents ORDER BY a.purpose;
```

Expect two rows, `AVAILABLE` and `HELD`. For each one, `kept` and `from_postings` are **equal**, and both match `/me` from check 3 (after check 2's top-up and check 3's hold, we saw 1405 available and 600 held). The kept number is what the Ledger decided with; the sum is the proof it is right. If the two columns differ, stop and run check 8.

### Check 5: one deal settles, and its totals match its charges

Twelve students join a deal, the clock moves past the cutoff, the deal confirms:

```bash
D=$(./sim deal-opens --cutoff 2 | grep -o 'Deal: [0-9a-f-]*' | cut -d' ' -f2)
for i in $(seq 31 42); do
  curl -s -X POST -H "Authorization: Bearer $(./token student0$i)" \
    -H "Idempotency-Key: $(cat /proc/sys/kernel/random/uuid)" \
    localhost:8080/api/orders/deals/$D/orders; echo
done
./clock advance 3m
```

Expect 12 lines with `"status":"CONFIRMED","heldCents":600`. About 10 s later, in the SQL console (`echo $D` for the value):

```sql
SELECT s.charged_count, s.total_charged_cents, s.total_commission_cents,
       COUNT(c.order_id) AS charges, COALESCE(SUM(c.price_cents), 0) AS charged,
       COALESCE(SUM(c.commission_cents), 0) AS commission
FROM deal_settlement s LEFT JOIN order_charge c ON c.deal_id = s.deal_id
WHERE s.deal_id = '<dealId>'
GROUP BY s.deal_id, s.charged_count, s.total_charged_cents, s.total_commission_cents;
```

Expect **one row**, `12, 6000, 600, 12, 6000, 600`: the settlement's totals equal the sum of that deal's charges. Those charges are the record the Ledger keeps for each order.

Then check where the money went. The rows must add up to 0:

```sql
SELECT a.owner_type, a.purpose, SUM(p.amount_cents) AS cents
FROM order_charge c
JOIN ledger_movement m ON m.reference_type = 'ORDER_CHARGE' AND m.reference_id = c.order_id
JOIN ledger_posting p ON p.movement_id = m.id JOIN ledger_account a ON a.id = p.account_id
WHERE c.deal_id = '<dealId>' GROUP BY a.owner_type, a.purpose;
```

Expect `RESTAURANT PAYABLE 5400`, `STUDENT AVAILABLE 1200`, `STUDENT HELD -7200`, `UNION COMMISSION 600`. Each order cost 500 of the 600 set aside: 450 goes to the restaurant, 50 to the Union, 100 comes back.

### Check 6: a settlement through a crash happens once

A settlement is one database transaction, so a crash either finishes all of it or none of it. The redelivered event then settles the deal once. Prove it with a real crash:

```bash
# dev-kit/: open a deal and let 12 students join, as in check 5, but don't advance the clock yet
docker kill ledger-1
./clock advance 3m
```

The surviving instance does the work. Bring the other back:

```bash
cd ../line-a-ledger && docker compose up -d
```

Expect a settled deal, never half of one:

```sql
SELECT COUNT(*) AS settlements, MAX(charged_count) AS charged FROM deal_settlement WHERE deal_id = '<dealId>';
SELECT COUNT(*) AS charges FROM order_charge WHERE deal_id = '<dealId>';
SELECT COUNT(*) AS open_holds FROM hold_decision WHERE deal_id = '<dealId>' AND state = 'OPEN';
```

Expect `1, 12`, then `12`, then `0`. A settlement that was cut off leaves no row: the retry settles the whole deal once.

The same rule, without killing anything: the deal's confirmation delivered twice.

```bash
./sim deal-confirmed --repeat
```

Expect the three queries above to still read `1, 12`, `12`, `0`: a second confirmation settles nothing new.

### Check 7: no hold after the deal closed

```bash
curl -s -X POST localhost:8081/internal/ledger/holds -H 'Content-Type: application/json' -d "{\"orderId\":\"$(cat /proc/sys/kernel/random/uuid)\",\"studentId\":\"$S\",\"dealId\":\"$D\",\"cutoffAt\":\"2026-11-15T12:00:00Z\",\"amountCents\":600}"; echo
```

Expect `"result":"DEAL_CLOSED"`.

### Check 8: the whole Ledger balances

All three rows must read 0:

```sql
SELECT 'all postings', COALESCE(SUM(amount_cents), 0) FROM ledger_posting
UNION ALL SELECT 'movements not summing to 0', COUNT(*) FROM (
  SELECT movement_id FROM ledger_posting GROUP BY movement_id HAVING SUM(amount_cents) <> 0) x
UNION ALL SELECT 'student balances not matching postings', COUNT(*) FROM (
  SELECT a.id FROM ledger_account a LEFT JOIN ledger_posting p ON p.account_id = a.id
  WHERE a.owner_type = 'STUDENT' GROUP BY a.id, a.balance_cents
  HAVING a.balance_cents <> COALESCE(SUM(p.amount_cents), 0)) y;
```

**Always last**, from `dev-kit/`: `./clock reset`

## 5. Look at the money

Same SQL console. Replace `<studentId>`, `<dealId>` and `<restaurantId>` with real values.

**A student's statement, oldest first.** `reference_id` is the topUpId, orderId or refundId:

```sql
SELECT m.posted_at, m.reference_type, m.reference_id, a.purpose, p.amount_cents, p.description
FROM ledger_posting p JOIN ledger_account a ON a.id = p.account_id JOIN ledger_movement m ON m.id = p.movement_id
WHERE a.owner_type = 'STUDENT' AND a.owner_id = '<studentId>'
ORDER BY m.posted_at, p.id;
```

**One deal, order by order:** what each order paid, gave back and had refunded:

```sql
SELECT order_id, student_id, price_cents, commission_cents, returned_cents, refunded_cents, charged_at
FROM order_charge WHERE deal_id = '<dealId>' ORDER BY charged_at;
```

**A restaurant's payable.** This is the only way to see it: a restaurant's `balance_cents` stays 0 and must never be read:

```sql
SELECT COALESCE(SUM(p.amount_cents), 0) FROM ledger_posting p JOIN ledger_account a ON a.id = p.account_id
WHERE a.owner_type = 'RESTAURANT' AND a.owner_id = '<restaurantId>' AND a.purpose = 'PAYABLE';
```

**The Ledger's own totals.** There is no API for these (§7); sum the postings:

```sql
SELECT a.purpose, SUM(p.amount_cents) AS cents
FROM ledger_posting p JOIN ledger_account a ON a.id = p.account_id
WHERE a.owner_type = 'UNION' GROUP BY a.purpose;
```

**Money that never finished moving.** Each row should be gone in seconds, not minutes:

```sql
-- holds still open on a closed deal
SELECT h.deal_id, dc.reason, COUNT(*) AS open_holds, MIN(h.decided_at) AS oldest
FROM hold_decision h JOIN deal_closure dc ON dc.deal_id = h.deal_id
WHERE h.state = 'OPEN' GROUP BY h.deal_id, dc.reason;

-- cash-outs reserved at the desk, waiting for Payments
SELECT withdrawal_id, student_id, amount_cents, decided_at FROM withdrawal
WHERE state = 'RESERVED';

-- refunds waiting for their order, and events not yet on Kafka
SELECT waits_for, event_id, parked_at FROM parked_event WHERE consumer = 'support-v1';
SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL;
```

If one sits there for **over 6 h**, the cause is upstream: a service never sent the event that completes it. Ask that team; don't fix it by hand.

## 6. Watch it

- **Grafana**: http://localhost:3000, folder "Dev kit", dashboard "Ledger". Set "Scrape job" to `services`.
- **Normal looks like**: holds almost always `PLACED` and under 2 s; one settlement per confirmed deal in about 0.1 s (12 orders); `ledger_settlement_issues_total` 0; `ledger_dead_letters` not growing; nothing parked or reserved for long.
- **A panel showing "No data" is not calm** — it means Prometheus can't read us. Treat it as "the Ledger or its database is down" and go to §8.
- Raw numbers from one instance: `curl -s localhost:8081/actuator/prometheus | grep '^ledger_'`

## 7. Known limitations

Recorded here as they are. None of these is being built now.

- **No API for the Ledger's own totals.** Union finance can read any single student's balance at the desk (`GET /api/ledger/students/{id}`), but nothing returns the Ledger's own totals: Union cash, commission earned, or the sum of everything owed to restaurants. Read them from the tables instead (§5). One `GET /api/ledger/totals` would close this; it is out of scope for now.
- An event outside the contract (a negative price, commission outside 0–10000) moves nothing, is retried for about 2.5 min, then goes to `ledger.dlt`.
- The same `orderId` listed twice for different students in one deal: the second is dropped, with no `SettlementIssue`.
- MySQL down for over 15 min: events that arrived meanwhile go to `ledger.dlt` and need a replay.
- Holds far above 100/s just after a restart: some answer 503 `UNAVAILABLE`. Orders retries.
- A deal of 2,000 orders settles in 3.6 to 6 s; holds for its students meanwhile answer 503 within 1.5 s. The load test's deals are 200.

The reasons and evidence for each: [HANDOVER.md §10](HANDOVER.md), [EDGE-CASES.md](EDGE-CASES.md).

## 8. When something breaks

| What you see | What it means | What to do |
|---|---|---|
| Holds answer 503 `UNAVAILABLE` | MySQL is down, or very busy | Nothing was saved. Restart MySQL; retries decide afresh |
| Holds answer 502 `NOTHING_ANSWERS` at the gateway | Both instances down, or the dev kit still points at the fake | `docker compose ps`, then redo §3 step 2 |
| `/api/ledger/hello` doesn't say `"service":"ledger"` | The dev kit is still serving the fake Ledger | §3 step 2, then `docker compose --profile full up -d --force-recreate gateway fakes` |
| `/me` answers 503 "Sign-in is briefly unavailable" | Sign-in's public key is unreachable | Wait; it recovers by itself |
| Events stop appearing on `ledger.v1` | Kafka is down. Nothing is lost: the outbox holds them | Restart Kafka, watch `SELECT COUNT(*) FROM outbox_event WHERE published_at IS NULL` fall to 0 |
| One instance is gone | Normal failure mode; the other carries on | `docker compose up -d` |
| A deal has no `deal_settlement` row after a crash | The transaction rolled back; nothing was half-charged | Nothing. The redelivery settles the whole deal once, then run check 6 |
| A message went to `ledger.dlt` | We couldn't process it after 5 tries | Note the `Dead letter:` log line, fix the cause, ask the platform team to replay |
| A container exits with code 137 | It hit its 512 MB cap | `docker compose logs --tail 40 instance-1`; run one instance while debugging |
| `network hackathon declared as external, but could not be found` | The dev kit isn't up; it creates the network | Start the dev kit (§3 step 2) |
| `Migration checksum mismatch` | An applied migration file was edited | Restore the file from git and redeploy. On a dev kit only: drop and recreate the `ledger` database (§9) |
| `pull access denied` for `cs4297.skynet.ie/...` | Not logged in | `docker login cs4297.skynet.ie` |
| `Could not resolve placeholder 'DB_URL'` | A setting is missing from `service.env` | Restore it; the defaults are the dev kit's values |

The Ledger is a **source of truth**. Never drop the database outside a dev kit: balances can always be recomputed from the postings, which is exactly what checks 4 and 8 do.

## 9. Stop and reset

All in `line-a-ledger/`:

| To | Run |
|---|---|
| Stop, keep the data | `docker compose down` |
| Start again | `IMAGE=cs4297.skynet.ie/hackathon-2026/line-a-ledger:release docker compose up -d --no-build` |
| Wipe the Ledger's data only | `docker compose down`, then `docker exec devkit-mysql-1 sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "DROP DATABASE ledger; CREATE DATABASE ledger;"'`, then start again |
| Wipe everything (dev kit, in `dev-kit/`) | `docker compose --profile full down -v`, then §3 from step 2 |
| Give the dev kit back to the fake Ledger | In `dev-kit/.env`: `LEDGER_URL=http://fakes:8080` and `FAKE_EXCLUDE=` empty, then `docker compose --profile full up -d` |

## 10. Go deeper

| You want | Read |
|---|---|
| Every detail, every scenario, every command | [HANDOVER.md](HANDOVER.md) |
| How it works and why | [ARCHITECTURE.md](ARCHITECTURE.md) |
| Tables and columns | [SCHEMA.md](SCHEMA.md) |
| Who else depends on us, and on what | [NEIGHBOURS.md](NEIGHBOURS.md) |
| The odd cases and what we chose to do | [EDGE-CASES.md](EDGE-CASES.md) |
| Proof from real runs | [evidence/](evidence/) |
| Words we use | [GLOSSARY.md](../GLOSSARY.md) |

Owner of this file: item 4 (Shakeeba).
