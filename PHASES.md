# Phases

Where the Ledger stands against the brief's build order. LED items: per-team spec, Ledger §5.

| Phase | Due | Scope | Status |
|---|---|---|---|
| 1 Slice 1 | Fri | Hold call, top-ups, `/me` | **Done**: LED-02, 03, 04, 07 |
| 2 Golden path | Sat 21:00 | Accounts, settlement, releases | **Done**: LED-01, 05, 06, 08, 09, 10, 22 |
| 3 Rest of the core | Sun 12:00 | Statement, desk, refunds, cash-outs, payouts | LED-11, 12, 13, 15, 16, 17 done; 14 partly |
| 4 Production bar | Sun 16:00 | Security, bad input, outages, dashboard, handover | LED-18, 19 done; 20, 23, 24 partly; 21 to do |

## Done

| LED | What | Proof |
|---|---|---|
| 01 | Accounts open on registration | `AccountsOpenTest` |
| 02 | Top-ups credited once | `LedgerTopUpTest`, `ContractTest`, live `money-in --students 204` |
| 03, 04 | Hold call, repeats, closed refusals | `HoldsTest` (13), live race on two replicas |
| 05, 06, 10 | Settlement exactly once, problems reported | `SettlementTest` (6), live golden path |
| 07 | `/me` | `LedgerCoreTest`, live via gateway |
| 08, 09 | Releases | `ReleasesTest` (4), live failed deal |
| 11 | Statement | `StatementTest` (9), live via gateway |
| 12 | Desk balance | `DeskTest` (4), live via gateway |
| 13 | Refunds, never over the charge | `RefundsTest` (5), `ContractTest`, live refund on the fake line |
| 15, 16 | Cash-outs: reserved, then paid out or returned, never both | `WithdrawalsTest` (8), `ContractTest`, live on the fake line |
| 17 | Payouts, once per payoutId | `WithdrawalsTest`, `ContractTest` |
| 18 | 401 before 403 on `/me`, statement and desk; 503 when sign-in key is down | `LedgerCoreTest`, `StatementTest`, `DeskTest`, `SignInUnavailableTest`, live via gateway |
| 19 | Bad hold input 422/413; zero-amount top-up, refund, cash-out and payout to `ledger.dlt` | `HoldsTest`, `ContractTest`, `PaymentsDeadLetterTest`, [evidence](docs/evidence/led-19-payments-dlt.md) |
| 22 | 100 holds/s, also during settlement | [k6 evidence](docs/evidence/led-22-holds-k6.md) |

## Partly done

| LED | Done | Left (item) |
|---|---|---|
| 14 | Early refunds parked | Wake on charge or release; NEVER_CHARGED (6) |
| 20 | Movements sum to zero, no negatives, chaining | Zero-sum property test, marked (7) |
| 23 | Hold and settlement metrics; Grafana dashboard live on the dev kit | Refund and DLT metrics, 6 h alert (4, 6) |
| 24 | Migrations, recompute and settlement SQL | Rest of the handover document (4) |

## To do

| LED | What | Item |
|---|---|---|
| 21 | Outage drills | 4 |

## Marked properties (20 marks)

| Property | Status |
|---|---|
| Movements sum to zero, no negative balance | Holds everywhere; property test still to write (item 7) |
| A deal settles exactly once, through a crash | Done (`SettlementTest`) |
| No order refunded more than charged | Done (`RefundsTest`: over the charge refused, part refunds round on the total); parked refunds still need waking (item 6) |
| A hold after its deal or order closed is refused | Done (`HoldsTest`) |

Each LED item has a Forgejo issue (#7 LED-01 to #30 LED-24): closed means done, open means work left. A PR that finishes one says `Closes #NN`.

## Next for the lead

`checkpoint-2` tagged (`6e8cce1`, the proof of concept: [POC-DEMO.md](docs/POC-DEMO.md)). Next: promote into integration; LED-14 (item 6), LED-20 property test (item 7), LED-21 outage drills (item 4) before Sun 12:00. Item 9 (edge cases) is a stretch.

## How we're marked

Handover §20. The project is 40% of the module, out of 100, over four checkpoints. Marks are locked from each checkpoint's tag.

| Checkpoint | When | Marks | Earned by | Where we are |
|---|---|---|---|---|
| Design | Sat 11:00 | 15 | Work split; data model; how we keep the properties; decisions with the options rejected | Tagged `checkpoint-1` |
| Proof of concept | Sat 15:50 | 15 | Slice and more on the fake line; CI passing; dashboard live | Slice and settlement done; dashboard to do |
| Integration | Sun 14:00 | 15 | Running in integration; our part of the journey end to end; failure handling | Promote by Sat 16:30 |
| Final | Sun 16:00 | 55 | Deploy gate, then handover document 10, marked properties 20, end to end and known limitations 15, individual 10 | Handover document, refunds, property test to do |

Each checkpoint is 10 team marks and 5 individual (from your own merged, reviewed work and issues, and your answers).

| Design (10) | Proof of concept (10) | Integration (10) |
|---|---|---|
| Work split 2 | Working slice 4 | Integration deployment 3 |
| Data and service design 3 | CI, tests, contracts 3 | End to end 3 |
| Properties and decisions 3 | Deployment and observability 2 | Failure handling and properties 2 |
| Task breakdown 2 | Progress and evidence 1 | Release discipline 2 |

**Levels:** each criterion scores 0 (no evidence), about a third, about two thirds, or full (complete, reliable, well explained).

**Marked properties:** 5 each. Passes and explained 5; passes, weak explanation 3.5; fails safely with a clear explanation 2.5; fails unsafely or no evidence 0.

**Deploy gate:** the assessor follows the handover document literally on a fresh Ubuntu machine with no Java.
- Running within 20 minutes from the document alone: passed.
- One intervention: passed, but costs 5 of the 10 handover marks.
- More, or not running after 20 minutes: failed, and only the individual share of the final is marked.

**Taken off:**
- **Bugs** found by test agents after the freeze: critical 5, major 2, minor 0.5 each, at most 25.
- **×0.75 each:** a secret committed anywhere in history; a binary over 1 MB without Git LFS.
- **Exam rule:** under a third on the exam's system-design section cuts the project mark by 20%.
- **Bonus:** up to 5 for working stretch items, only once the core is complete; capped at 100.

## What to know

| When | What |
|---|---|
| Sat 11:00 | Design review (line A); promotion opens |
| Sat 15:50 | Proof-of-concept check |
| Sat 16:30 | Orders needs our hold call; desk top-ups need TopUpCredited |
| Sat 21:00 | Golden path on the five real services |
| Sun 14:00 | All-real golden path: the integration mark |
| Sun 16:00 | Code freeze; final assessment (line A 16:00–17:00) |

- Tag `checkpoint-1`, `checkpoint-2`, `checkpoint-3`, then `release`. Marks come from the tag.
- Every PR is reviewed by someone other than its author, and says which prompts were used (handover §18.4).
- To promote, the lead posts `promote ledger <tag>` in the line's integration channel.
- In the final, each person gets about 2 minutes of questions on their own area, how it connects with the others, and the team's decisions.
