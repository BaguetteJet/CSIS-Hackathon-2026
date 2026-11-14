# Proof-of-concept demo

About 10 minutes, on one laptop, from the tag `checkpoint-2`. It shows every built money path live on the fake line, the books balancing, and the dashboard. Each step's expected output is from the run on Sat 14 Nov ([evidence](evidence/poc-integrated-2026-11-14.md)); IDs differ.

## Before the demo (once)

| Step | Run | Ready when |
|---|---|---|
| Dev kit up, our Ledger behind the gateway | HANDOVER §4 step 2 (`LEDGER_URL=http://ledger:8080`, `FAKE_EXCLUDE=ledger`, copy `grafana/ledger.json`, `docker compose --profile full up -d`) | http://localhost:8080 shows a page |
| Both instances from the tag | In `line-a-ledger`: `git checkout checkpoint-2`, then `./check` (JDK 21) | `Ready: tests pass and two instances run healthy within 512 MB each.` |
| Grafana | http://localhost:3000, folder "Dev kit", dashboard "Ledger", "Scrape job" `services` | Panels draw |

If port 9090 is already taken on your laptop, Prometheus won't start: run it on another host port with a compose override (`ports: !override ['127.0.0.1:9091:9090']`). Grafana reaches it inside the network, so nothing else changes.

Shortcuts for the steps below, in the dev kit folder:

```bash
me() { curl -s -H "Authorization: Bearer $(./token $1)" localhost:8080/api/ledger/me; echo; }
sql() { docker exec devkit-mysql-1 sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" ledger -N -e \"$1\""; }
PUB=../line-a-ledger/scripts/publish-sample.sh
```

## The demo

| # | Shows | Run | Expect |
|---|---|---|---|
| 1 | The gateway reaches our two instances | `curl -s -H "Authorization: Bearer $(./token student011)" localhost:8080/api/ledger/hello` | `"service":"ledger"`, `"instance":"ledger-1"` or `"ledger-2"` |
| 2 | Top-ups credited once (LED-02) | `./sim money-in --students 204`, then `me student011` | `availableCents` up by 2000 |
| 3 | One hold, asked three times on both instances, moves money once (LED-03, 04) | HANDOVER §5 step 4 | `PLACED` three times; `me student011`: 600 less available, 600 held |
| 4 | Short of money and bad input refused | HANDOVER §5 step 5 | `INSUFFICIENT_FUNDS` with `availableCents`; 422 `VALIDATION_FAILED` naming five fields |
| 5 | Golden path: 12 join, the deal tips, the Ledger settles once (LED-05, 06) | HANDOVER §5 step 6 (keep `$D`) | 12 × `CONFIRMED`; after `./clock advance 3m`: `sql "SELECT charged_count, total_charged_cents, total_commission_cents FROM deal_settlement WHERE deal_id='$D'"` gives `12 6000 600` |
| 6 | A hold after the deal closed is refused (marked) | HANDOVER §5 step 7 | `DEAL_CLOSED` |
| 7 | The statement nets to the balance (LED-11) | `curl -s -H "Authorization: Bearer $(./token student031)" localhost:8080/api/ledger/me/entries` | `RETURNED +100` "Paid €5.00, €1.00 back in your Account", `HOLD −600` "Set aside for your order", `TOP_UP +2000`; their sum equals `me student031` |
| 8 | Refund, never over the charge (LED-13, marked) | `O=$(sql "SELECT order_id FROM order_charge WHERE deal_id='$D' LIMIT 1"); RS=$(sql "SELECT student_id FROM order_charge WHERE order_id='$O'")`, then `$PUB support.v1 RefundApproved $RS "{\"refundId\":\"$(uuidgen \| tr A-Z a-z)\",\"orderId\":\"$O\",\"dealId\":\"$D\",\"studentId\":\"$RS\",\"amountCents\":200}"`; repeat with `600` | `sql "SELECT amount_cents, commission_reversed_cents, outcome FROM refund WHERE order_id='$O'"`: `200 20 POSTED`, then `600 0 EXCEEDS_CHARGE` |
| 9 | Desk balance for Union finance (LED-12) | `S=` student011's `studentId` from step 2; `curl -s -H "Authorization: Bearer $(./token union-finance)" localhost:8080/api/ledger/students/$S` | The same numbers as `me student011`; with a student token: 403 |
| 10 | Cash-out: set aside, then paid out (LED-15, 16) | `W=$(uuidgen \| tr A-Z a-z); F=$(uuidgen \| tr A-Z a-z)`; `$PUB payments.v1 WithdrawalRequested $S "{\"withdrawalId\":\"$W\",\"studentId\":\"$S\",\"amountCents\":500,\"staffId\":\"$F\"}"`, `me student011`; then the same with `WithdrawalCompleted` | After the request: 500 less available, 500 more held; after completion: held back down by 500. `sql "SELECT state FROM withdrawal WHERE withdrawal_id='$W'"`: `PAID_OUT` |
| 11 | The books balance (marked) | HANDOVER §8 "Is the whole Ledger balanced?" | Every row 0 |
| 12 | Observability | The Grafana dashboard | Holds per second by result and by instance, hold latency well under 2 s, settlements, SettlementIssue 0 |
| 13 | Always last | `./clock reset` | `"offsetSeconds":0` |

In zsh, split a two-column `sql` result with `cut -f1` and `cut -f2`; `set --` doesn't split it.

## What to say

- Every movement is postings that sum to zero, so step 11 is the proof for all the others (decision 0002).
- Repeats and races change nothing: business keys and the first-answer table (decisions 0003, 0007, 0008).
- Settlement is one transaction: a crash charges nothing, and redelivery settles once (`SettlementTest`).
- Not built yet: waking early refunds (LED-14), the zero-sum property test (LED-20), outage drills (LED-21). [PHASES.md](../PHASES.md).
