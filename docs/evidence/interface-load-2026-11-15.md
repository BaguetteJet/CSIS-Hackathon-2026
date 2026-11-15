# Interface targets under load, 15 Nov 2026

Sun 15 Nov, 06:40–06:48 UTC. Dev kit with `FAKE_EXCLUDE=ledger`, both instances from `./check` at `e27571f`, 200 students funded with `./sim money-in --students 204`. Targets: [INTERFACE-TARGETS.md](../INTERFACE-TARGETS.md).

```bash
python3 -I scripts/drills/load_checks.py <dev-kit dir> . <dir with students.json, finance.txt>
```

Each run: [`scripts/k6/rush.js`](../../scripts/k6/rush.js) sends 100 holds/s for 20 s over 10 deals (each student once per deal, the PLT-14 shape), 30 page calls/s (`/me`, `/me/entries`) and 5 desk calls/s through the gateway. 20 top-ups arrive mid-rush, and deal 1 (200 orders) is confirmed 4–6 s in, while holds for deals 2–10 go on for the same students. Then deal 2 (200 orders) settles alone, deals 3–10 are cancelled (1,600 releases), and 300 RefundApproved arrive at once.

Settlement time is the instances' own `ledger_settlement_seconds` timer for that one settlement. The other times run from before the send, so they include about 1 s of starting Kafka's console producer.

## Clean runs

| | Run 3: quiet | Run 4: 8 of 10 CPUs busy (`yes` ×8) |
|---|---|---|
| Holds: answers | 2,000, all 200 PLACED | 2,000, all 200 PLACED |
| Holds: med / p95 / p99 / max | 6 / 70 / 200 / 289 ms | 11 / 101 / 200 / 330 ms |
| `/me`: p95 / max | 32 / 216 ms | 27 / 220 ms |
| `/me/entries`: p95 / max | 40 / 277 ms | 32 / 190 ms |
| Desk: p95 / max | 35 / 213 ms | 39 / 164 ms |
| Page and desk answers | 701, all 200 | 701, all 200 |
| 200-order settlement during the rush | 0.209 s | 0.356 s |
| 200-order settlement alone | 0.364 s | 0.270 s |
| 20 top-ups mid-rush, all credited | 1.19 s | 2.03 s |
| 1,600 releases (8 deals cancelled) | 4.71 s | 4.59 s |
| 300 refunds, all POSTED | 1.94 s | 3.58 s |
| Outbox empty after the last refund | 2.07 s | 8.20 s |
| Books: postings sum to 0; no balance below 0 | Yes | Yes |
| Memory after (limit 512 MiB) | 457 / 441 MiB | 445 / 437 MiB |

## Earlier runs, same morning

| Run | What differed | Holds p95 / max | Settlement during the rush | Notes |
|---|---|---|---|---|
| 1 (06:40) | First version of the script | 261 / 950 ms | 0.417 s (timer max) | `/me` p95 209 ms; releases 6.49 s, refunds 1.53 s, both timed after the send. Top-up time not valid |
| 2 (06:42) | Another session's k6 (300/s) overlapped releases and refunds from 06:42:40; refunds keyed by order, not student | 90 / 459 ms | **0.874 s** | Rush phase clean. Under the overlap: refunds 45 s, outbox lag up to 57 s |

## What it shows

- No hold took longer than 950 ms in 8,000 hold calls, and none answered 503, including during a 200-order settlement on the same students.
- A 200-order settlement during the rush took 0.21–0.87 s across four runs. The worst is 0.13 s under the 1 s a hold waits for its student. Busy CPUs didn't slow it; the 0.874 s came from run 2's rush phase.
- `SettlementTest`'s 2.6–3.1 s (EDGE-CASES Q2, X10) wasn't seen live. That test runs on Testcontainers' MySQL alongside the other container tests.
- At about three times the spec's rate (run 2's overlap), refunds took 45 s and events reached Kafka up to 57 s late. Both are still under 60 s, and nothing was lost or doubled.

**Not yet:** the integrated line (PLT-14 in a booked slot); a deal over 200 orders (X9); the outbox lag at exactly 300/s on its own.
