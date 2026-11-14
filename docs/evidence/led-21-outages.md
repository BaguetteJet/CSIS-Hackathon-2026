# LED-21: outage drills

Sat 14 Nov, 19:46–20:06 UTC, dev kit with `FAKE_EXCLUDE=ledger`, both instances from `./check` at `357781a` (item-4/metrics-handover). Script: [`scripts/drills/led21_drills.py`](../../scripts/drills/led21_drills.py) (publishes contract envelopes with the broker's console producer and calls the hold route directly).

```bash
python3 -I scripts/drills/led21_drills.py <dev-kit dir> .
```

| Drill | Spec (LED-21) | Measured | Result |
|---|---|---|---|
| Kafka paused 5 min 3 s (`docker pause`) | Holds answered; events wait in the outbox, then go out in per-key order | 40 hold calls, all PLACED, slowest 0.18 s; 40 events waited; outbox drained 7 s after unpause; publish order = outbox order | Pass |
| MySQL stopped 2 min 13 s | 503 UNAVAILABLE, never a hang over 10 s or a 500; normal within 60 s | 30 calls (hold, `/me`, `/me/entries`) all 503 UNAVAILABLE, slowest 1.18 s; both instances normal 8 s after MySQL returned | Pass |
| Same: a valid top-up sent while MySQL was down | Not dead-lettered | Credited 51 s after MySQL returned (consumer retry); `ledger_dead_letters` 2 → 2 | Pass |
| `ledger-1` killed (`docker kill`) | The other carries on; events delivered twice change nothing | 116 hold calls on `ledger-2`, all PLACED, slowest 0.24 s; 3 top-ups each sent twice credited once (600 = 600, one TopUpCredited each); `ledger-1` healthy 10 s after restart | Pass |
| Both instances stopped 10 min 7 s | Settlements, top-ups and releases catch up from the topics, each once | Sent while down: a top-up 3 times (twice one envelope, once a new one), DealConfirmed twice, DealFailed, OrderWithdrawn. Healthy 13 s after start; balance 3300 / 1700 → 5000 / 0 within 2 s; one TopUpCredited, one DealSettled, two OrderCharged, one HoldReleased per release | Pass |
| Books after all four | Every posting sums to 0 | `SUM(amount_cents)` = 0 | Pass |

Not covered here: Orders' side (keeping the order pending while the hold call answers 503) is Orders' behaviour.
