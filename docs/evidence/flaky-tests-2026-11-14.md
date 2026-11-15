# Flaky container tests, 14 Nov 2026

Two full `./gradlew containerTest --rerun` runs on `ffeefa5` (the LED-14 to LED-24 stack, PRs #56–#60), on a laptop also running the dev kit and both Ledger instances. Each run had one different failure; both pass alone.

| Run | Result | Failed |
|---|---|---|
| 1 | 94 tests, 1 failed | `ReleasesTest` "holds racing a failing deal all end refused or released" |
| 2 | 94 tests, 1 failed | `ConsumerPatternsTest` "an event arriving before its prerequisite is parked, then applied" |
| Alone | `ReleasesTest` race 3 of 3 passed; `ConsumerPatternsTest` 2 of 2 passed | — |

## Causes

| Test | What happened | Cause | Done |
|---|---|---|---|
| `ReleasesTest` race | A hold threw `ApiProblem` 503 `UNAVAILABLE` | A hold waiting over 1 s behind the deal's release answers 503 and saves nothing, by design (EDGE-CASES H10, X11). The test took a 503 as a failure; Orders retries the same `orderId` | The test retries a 503 with the same `orderId`, up to 5 calls, as Orders does. Every other assertion is unchanged. 3 of 3 passed with 8 busy CPU loops running (branch `item-1/flaky-tests`); no 503 came up in those runs, so the retry itself is checked by reading only |
| `ConsumerPatternsTest` parking | `NoSuchElementException` at line 79 | `findById(...).orElseThrow()` inside `untilAsserted`: Awaitility retries only `AssertionError`, so a user row not yet written fails at once instead of waiting. A test bug; the parking it checks works | Fixed on `main` in `09869a8` (Sam): the missing row is now an assertion, so Awaitility polls again |

## Load-sensitive by design

These assert a real limit, so a busy laptop can fail them. Rerun alone before blaming the code; a failure alone is real.

| Test | Limit | Why the limit |
|---|---|---|
| `SettlementTest` "a deal at its cap settles ... in under a second" | 200 orders in 1 s | A hold waits at most 1 s for its students ([tests-2026-11-14.md](tests-2026-11-14.md)) |
| `HoldsTest` "a student locked for longer than Orders waits answers 503 within 2 seconds" | 503 within 2 s | Orders waits 2 s |
| `LedgerCoreTest` second top-up while another is slow | Under 1 s | Top-ups for different students don't wait for each other |

CI runs `test` and `contractTest` only, so none of these run there. Run 1's `test` and `contractTest` on `ffeefa5`: 24 and 13 tests, all passed.

**Follow-up, Sat 14 Nov, 21:20, `main` at `7ac810f`:** `SettlementTest` "a deal at its cap" run alternately on `09869a8` (before the LED-14 to 24 merges) and `7ac810f` (after), load average about 11.5 (dev kit running, an unrelated crash-looping Kubernetes pod): before 1333, 1251, 1661 ms; after 1580, 1196, 1546 ms. The merges did not slow settlement; the laptop's load did. `ZeroSumPropertyTest` failed once (a hold answered 503 while two container suites ran at once), then passed 6 of 6 alone.

**Follow-up, Sun 15 Nov:** #63 (Igor) raised that test's limit from 1 s to 3 s, to match the brief.
