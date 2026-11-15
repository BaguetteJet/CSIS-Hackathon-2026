# Checklist: the brief against what's built

**As of** Sun 15 Nov, `main` at `e27571f`. Each LED row is the brief's "Done when" (team spec, Ledger §5; copied into Forgejo issues #7 to #30) and the test or evidence that shows it. Status of record: [PHASES.md](PHASES.md).

✅ done · ⚠️ partly · ⬜ not yet

## LED items (24 of 24 done)

| | LED | Done when (brief) | Shown by |
|---|---|---|---|
| ✅ | 01 Open accounts | Registration opens accounts once; a late one changes no balance | `AccountsOpenTest` |
| ✅ | 02 Credit top-ups | The same topUpId twice: one credit, one event | `LedgerTopUpTest`, `LedgerCoreTest`, `ContractTest` |
| ✅ | 03 Place a hold | Exact funds placed, one cent short refused, answer within 2 s | `HoldsTest`; k6 ([evidence](docs/evidence/led-22-holds-k6.md)) |
| ✅ | 04 Repeats and refusals | Repeats and races give one answer; ORDER_CLOSED, then DEAL_CLOSED, then funds | `HoldsTest` (both instances) |
| ✅ | 05 Settle a confirmed deal | The worked example exact: 48 / 427 / 125, and 4525 / 0 | `SettlementTest` "the worked example to the cent" |
| ✅ | 06 Settle exactly once | A crash halfway charges nothing; the retry settles once | `SettlementTest` crash and repeat tests |
| ✅ | 07 My balance | 401 without a token, 403 for the wrong role, zeros for no account | `LedgerCoreTest`, `LedgerTopUpTest` |
| ✅ | 08 Release an order | One HoldReleased per hold; a later hold is ORDER_CLOSED | `ReleasesTest`, `HoldsTest` |
| ✅ | 09 Release a deal | Each hold released once; never automatically after 6 hours | `ReleasesTest`; no release timer exists ([EDGE-CASES](docs/EDGE-CASES.md): never for time) |
| ✅ | 10 Settlement problems | The rest of the deal settles; DealSettled leaves it out | `SettlementTest` "listed orders that can't be charged" |
| ✅ | 11 My statement | Credits minus debits equal availableCents | `StatementTest` (newest first, at most 100, student words) |
| ✅ | 12 Desk balance | Other roles 403, a malformed ID 404, an unknown one zeros | `DeskTest` |
| ✅ | 13 Post refunds | 450 with 45 commission, refunded 5 then 445, reverses 1 then 44 | `RefundsTest` "part refunds round on the running total" |
| ✅ | 14 Refund limits, early refunds | Over the charge is EXCEEDS_CHARGE; an early refund posts after the charge | `RefundsTest` (11 tests, NEVER_CHARGED too) |
| ✅ | 15 Reserve a cash-out | A cash-out racing a hold has exactly one winner | `WithdrawalsTest` "racing an order for the same money" |
| ✅ | 16 Pay out or return | The first of Completed or Failed wins; repeats change nothing | `WithdrawalsTest` |
| ✅ | 17 Restaurant payouts | The same payoutId twice debits once | `WithdrawalsTest`, `ContractTest` |
| ✅ | 18 Security | 401, then 403, on every page route; 503 if sign-in is down | `LedgerCoreTest`, `StatementTest`, `DeskTest`, `SignInUnavailableTest` |
| ✅ | 19 Bad input | 422 naming every field, 413 over 64 KB, every zero-amount event on `ledger.dlt` | `HoldsTest`, `PaymentsDeadLetterTest`, `ContractTest` |
| ✅ | 20 Two instances, one truth | The zero-sum property test passes (marked) | `ZeroSumPropertyTest` ([evidence](docs/evidence/led-20-zero-sum.md)) |
| ✅ | 21 Outages | Each drill recorded with its commands and results | [Drill evidence](docs/evidence/led-21-outages.md), `DatabaseUnavailableTest` |
| ✅ | 22 Speed | k6 passes alone and during settlement | [k6 evidence](docs/evidence/led-22-holds-k6.md) |
| ✅ | 23 Observability | Hold, settlement, refund and dead-letter panels; alert on a refund parked over 6 h | `grafana/ledger.json`, `grafana/alerting/ledger.yml`, `LedgerMetricsTest` |
| ✅ | 24 Operability and data | The handover document is complete | [HANDOVER.md](docs/HANDOVER.md), all 11 sections; §5 step 10's CI link waits for `release` |

Every LED issue is closed. Marked properties: [PHASES.md](PHASES.md#marked-properties-20-marks), all four done.

## Checkpoints

| | Checkpoint | Due | Status |
|---|---|---|---|
| ✅ | Design | Sat 11:00 | `checkpoint-1` |
| ✅ | Proof of concept | Sat 15:50 | `checkpoint-2` (`13e63ea`, 16:19, with the advisor's agreement) |
| ⬜ | Integration | Sun 14:00 | Tag `checkpoint-3` with green CI, post `promote ledger checkpoint-3`, then the all-real golden path |
| ⬜ | Final | Sun 16:00 | Below |

## Before the final

| | What | Owner |
|---|---|---|
| ✅ | All 24 LED items merged; CI green on `main` | |
| ✅ | No secret in history; no binary over 1 MB (checked Sat 14 Nov) | |
| ✅ | Known limitations written ([HANDOVER §10](docs/HANDOVER.md#10-known-limitations)) | |
| ⬜ | A teammate deploys from HANDOVER alone on a clean machine, using a CI `sha-` image (item 4 step 7) | Pierce, Shakeeba |
| ⬜ | Tag `release` with green CI; add its link to HANDOVER §5 step 10 | Shakeeba, Sam |
| ⬜ | Dev kit on every laptop: `/api/ledger/hello` answers from `ledger-1` or `ledger-2` (item 4 step 3) | Everyone |
| ⚠️ | A review by someone other than the author: #56 to #61 have none; #64 to #68 (merged Sun 08:08–08:10, `7816b08`) were reviewed only by the author's Claude Code session, at Hersh's request, with the whole stack green first | Each area's backup |
| ⬜ | About 2 minutes of questions each on your area, how it connects, and the decisions | Everyone |

## Work items

| | Item | Left |
|---|---|---|
| ⚠️ | [1](work-items/item1_hersh.md) Holds, integration | Promote (step 8); watch the contracts (step 9) |
| ✅ | [2](work-items/item2_igor.md) Core and data | |
| ✅ | [3](work-items/item3_sam.md) Payments events | |
| ⚠️ | [4](work-items/item4_shakeeba.md) Operations | Dev kit everywhere (3), tags (4), handover deploy by a teammate (7) |
| ✅ | [5](work-items/item5_settlement.md) Settlement | |
| ✅ | [6](work-items/item6_refunds.md) Refunds | |
| ✅ | [7](work-items/item7_statement.md) Statement | |
| ✅ | [8](work-items/item8_cashouts.md) Cash-outs | |
| ✅ | [9](work-items/item9_robustness.md) Edge cases (stretch, bonus only) | |
