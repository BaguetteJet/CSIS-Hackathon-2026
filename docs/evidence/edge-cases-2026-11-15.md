# Edge cases, faster tests and the fake line, 15 Nov 2026

Sun 15 Nov, 00:30–01:20 UTC, branch `item-9/edge-cases` on `main` `e27571f`. Laptop: 16 GB, swap nearly full, load average 7 to 11, dev kit and both Ledger instances running.

## Tests

| Run | Result |
|---|---|
| `./gradlew test contractTest containerTest --rerun-tasks` on `main` before the change | 24 + 13 + 94 passed; `containerTest` 169 s in tests, 133 s of it `ZeroSumPropertyTest` (200 runs) |
| `./gradlew containerTest --rerun`, after (quiet) | 100 passed, 56 s in tests; `ZeroSumPropertyTest` 17 s (50 runs), `EdgeCasesTest` 3.7 s |
| The same twice more, both Ledger instances up, load 9 and 11 | 102 passed both times, 164 s and 138 s wall; 200 orders settled in 2789 ms and 1892 ms |
| `EdgeCasesTest` alone, 3 times in a row | 8 passed each time, 11 s |

**The invariants can fail.** A throwaway test broke each inside a rolled-back transaction and called `assertBooksBalance()`: a held balance without its hold, caught by I3; a charged hold set back to OPEN, caught (first by I3); settlement totals off by 1 cent, caught by I5; an available balance off by 1 cent, caught by I1/I2. The test was deleted.

**A busy laptop made holds answer 503 in test setup** (`SettlementTest`'s 200 holds, `ZeroSumPropertyTest`, the release race). `LedgerFlowTest.hold()` now retries a 503 with the same `orderId`, as Orders does; a 503 saves nothing (EDGE-CASES H10).

## The fake line (dev kit, `FAKE_EXCLUDE=ledger`)

HANDOVER §5 steps 1 to 9, as written (macOS `uuidgen` form):

| Step | Seen |
|---|---|
| 1 Gateway | `"service":"ledger","instance":"ledger-1"` |
| 2, 3 Money in | `availableCents` 0 → 2000 |
| 4 One hold, three calls, two instances | `PLACED` three times; available −600, held +600 once |
| 5 Short, bad input | `INSUFFICIENT_FUNDS` with `availableCents` 1400; 422 `VALIDATION_FAILED` naming all five fields |
| 6 Golden path | 12 × `CONFIRMED` with `heldCents` 600; settlement 12 / 6000 / 600, matching the charges |
| 7 Late hold | `DEAL_CLOSED` |
| 8 Books | 0, 0, 0 |

Then `./sim deal-fails`, `kitchen-fails`, `support-actions`, `ledger-answers` on the settled deal: DealFailed after settlement released nothing (2 → 2 released holds); 12 refunds POSTED, 1 NEVER_CHARGED; no refund left parked; books 0, no student below 0.

**Found and fixed:** each scrape logged Kafka's `DescribeTopicPartitions API is not supported` WARN from the dead-letter gauge, 28 and 24 a minute on the two instances. The partitions are now described once: 0 a minute after, and `ledger_dead_letters` still 2.
