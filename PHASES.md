# Phases

Where the Ledger stands against the brief's build order. LED stories: per-team spec, Ledger §5.

| Phase | Due | Scope | Status |
|---|---|---|---|
| 1 Slice 1 | Fri | Hold call, top-ups, `/me` | **Done**: LED-02, 03, 04, 07 |
| 2 Golden path | Sat 21:00 | Accounts, settlement, releases | **Done**: LED-01, 05, 06, 08, 09, 10, 22 |
| 3 Rest of the core | Sun 12:00 | Statement, desk, refunds, cash-outs, payouts | To do: LED-11, 12, 13, 14, 15, 16, 17 |
| 4 Production bar | Sun 16:00 | Security, bad input, outages, dashboard, handover | Partly: LED-18, 19, 20, 21, 23, 24 |

## Done

| LED | What | Proof |
|---|---|---|
| 01 | Accounts open on registration | `AccountsOpenTest` |
| 02 | Top-ups credited once | `LedgerTopUpTest`, `ContractTest`, live `money-in --students 204` |
| 03, 04 | Hold call, repeats, closed refusals | `HoldsTest` (13), live race on two replicas |
| 05, 06, 10 | Settlement exactly once, problems reported | `SettlementTest` (6), live golden path |
| 07 | `/me` | `LedgerCoreTest`, live via gateway |
| 08, 09 | Releases | `ReleasesTest` (4), live failed deal |
| 22 | 100 holds/s, also during settlement | [k6 evidence](docs/evidence/led-22-holds-k6.md) |

## Partly done

| LED | Done | Left (item) |
|---|---|---|
| 18 | `/me` 401/403; 503 when sign-in key is down | Statement and desk routes (7) |
| 19 | Hold input 422/413; zero top-up to DLT | Zero-amount cash-out, refund, payout (3, 6) |
| 20 | Movements sum to zero, no negatives, chaining | Zero-sum property test, marked (7) |
| 23 | Hold and settlement metrics | Refund and DLT metrics, dashboard, 6 h alert (4, 6) |
| 24 | Migrations, recompute and settlement SQL | Rest of the handover document (4) |

## To do

| LED | What | Item |
|---|---|---|
| 11 | Statement | 7 |
| 12 | Desk balance | 7 |
| 13, 14 | Refunds, limits, parking (marked: no over-refund) | 6 |
| 15, 16 | Cash-outs | 8 |
| 17 | Payouts | 8 |
| 21 | Outage drills | 4 |

## Marked properties (20 marks)

| Property | Status |
|---|---|
| Movements sum to zero, no negative balance | Holds everywhere; property test still to write (item 7) |
| A deal settles exactly once, through a crash | Done (`SettlementTest`) |
| No order refunded more than charged | To do (item 6) |
| A hold after its deal or order closed is refused | Done (`HoldsTest`) |

## Next for the lead

Push and PR `slice-1/hold-api`; tag `checkpoint-1` before Sat 11:00; promote by 16:30.
