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

**Follow-up, Sun 15 Nov 06:10–06:30 UTC, `e00940f` (Q11, Q12, Q13 fixed; [EDGE-CASES](../EDGE-CASES.md#cases-that-cause-issues)).** All four drills passed again. Kafka paused 5 min: 40 holds PLACED, slowest 0.20 s, outbox drained 2 s after unpause; a top-up sent next took 44 s to credit (the consumers rejoining, Q21). MySQL stopped 2 min 13 s: 30 calls all 503 UNAVAILABLE, slowest 1.42 s; normal 8 s after; the top-up sent meanwhile credited at once (was 51 s: retried every 10 s now, not with growing waits). `ledger-1` killed: 37 holds PLACED on `ledger-2`, slowest 0.18 s; 600 = 600. Both stopped 10 min 7 s: healthy 24 s after start, 5000 / 0 within 7 s, each event once. Postings sum to 0.

**Follow-up, Sat 14 Nov 18:34–20:42 UTC, `13e63ea` (before #59), the four drills by hand, Kafka paused 10 min 56 s, `ledger-2` killed.** All four passed, nothing lost, each event applied once. Three findings this run doesn't show:
- Kafka paused: one HoldPlaced (`49f75289-4c37-474b-86e3-a7d59dba2796`) went out twice when Kafka came back, on `ledger.v1` partition 5 at offsets 800 and 820, about 1 s apart. A send waits up to 60 s for Kafka (the producer's `max.block.ms` default), longer than the outbox's 10 s lease: `ledger-2` took the lease at 18:55:53 while `ledger-1` kept sending until 19:02:01, so both sent the first waiting row. Consumers skip the repeat by eventId. A fix is configuration only: `max.block.ms` under 10 s (item 1).
- `ledger-2` killed with `docker kill` just after 100 top-ups arrived: 42 of them, on its partitions, waited 45 s for Kafka's session timeout before `ledger-1` applied them, each once. The same wait as Q7 and Q21, for one instance.
- MySQL stopped 4 min 52 s: ten holds, all 503 and none placed, seven in about 1 s and three in 1.98, 2.97 and 3.34 s, the first call to each instance among them. Under the 10 s target, but slower than the 1.18 s above; not explained, and this run was before #59. To measure again on `main`.
