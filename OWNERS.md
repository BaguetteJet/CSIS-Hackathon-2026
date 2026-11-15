# Owners

**Team lead:** Hersh (also integration: promotions, contracts, answers log).

We split the Ledger **by money flow**: each area owns one way money enters or leaves (the hold call, payments events, settlement, refunds...) with its own listener or API and its own tables. Each LED item then sits with one person instead of crossing three layers. Why, and the option we rejected: [decision 0001](docs/decisions/0001-work-split.md). Steps and dependencies: [work-items/](work-items/README.md). Progress: [PHASES.md](PHASES.md).

**Rules we keep** (the organisers' handover brief, §16):
- Every area has one owner and one backup.
- Everyone owns at least one area and backs up another.
- Every LED item has one owner, shown as the assignee of its Forgejo issue.

## Areas

| Area | Owner | Backup | LED items | Slice 1 | Item |
|---|---|---|---|---|---|
| Holds, integration | Hersh | Igor | 01, 03, 04, 18 (sign-in down), 22 (holds), 24 (SQL) | Yes: hold call | [1](work-items/item1_hersh.md) |
| Core and data | Igor | Pierce | 07, 20 (posting), 24 (migrations) | Yes: schema, posting, `/me` | [2](work-items/item2_igor.md) |
| Payments events, contracts | Sam | Md Hossain | 02, 19 | Yes: top-ups | [3](work-items/item3_sam.md) |
| Operations | Shakeeba | Sam | 21, 22 (k6), 23, 24 (handover) | Yes: demo and evidence | [4](work-items/item4_shakeeba.md) |
| Settlement, releases | Hersh | Cian | 05, 06, 08, 09, 10 | No: golden path | [5](work-items/item5_settlement.md) |
| Refunds | Hersh (from Md Hossain, Sat) | Cian | 13, 14 | No | [6](work-items/item6_refunds.md) |
| Statement, desk, testing | Pierce | Shakeeba | 11, 12, 18 (pages), 20 (property test) | No | [7](work-items/item7_statement.md) |
| Cash-outs, payouts | Cian | Hersh | 15, 16, 17 | No | [8](work-items/item8_cashouts.md) |
| Edge cases, robustness | Cian | Igor | 06, 19, 20, 21 (with item 4); every marked property | No | [9](work-items/item9_robustness.md) |

## Each person

**Hersh** (team lead)
- Owns: holds (item 1), settlement and releases (item 5), refunds (item 6, from Md Hossain on Sat). Backs up: cash-outs (item 8).
- Done: the hold call (LED-03, 04), accounts on registration (01), releases and settlement (05, 06, 08, 09, 10), refunds never over the charge (13), early refunds woken or refused NEVER_CHARGED (14), the load test (22), the sign-in 503 fix (18), 503 with MySQL down and the outage drills (21), refund and dead-letter metrics with the 6 h alert (23), the recompute SQL and the daily check (24); the proof of concept (`checkpoint-2`, [POC-DEMO.md](docs/POC-DEMO.md)); item 9's edge-case tests and invariants. Items 5 and 6 complete.
- Next: promote into integration and tag `checkpoint-3`; the all-real golden path by Sun 14:00; confirm [NEIGHBOURS.md](docs/NEIGHBOURS.md) with each team lead.

**Igor**
- Owns: core and data (item 2). Backs up: holds (item 1) and edge cases (item 9).
- Done: the schema, the posting service and `/me` (LED-07). Item 2 complete (the daily check, step 4, was added by Hersh).
- Next: review item 9's invariants with Cian (I1 and I2 reuse your daily check).

**Sam**
- Owns: payments events and contract tests (item 3). Backs up: operations (item 4).
- Done: top-ups credited once (LED-02); zero-amount top-ups, cash-outs and payouts to the dead-letter topic (19); the payments routing item 8 builds on; contract tests for the new events; the reset at 204 students. Item 3 complete.
- Next: back up item 4: the handover document and the `release` tag.

**Shakeeba**
- Owns: operations (item 4). Backs up: statement and testing (item 7).
- Done: the slice-1 demo bench and its evidence; the Grafana dashboard (LED-23); `checkpoint-2`.
- Next: a teammate's deploy from the handover document (item 4 step 7), and the `release` tag, by Sun 16:00.

**Cian**
- Owns: cash-outs and payouts (item 8), edge cases and robustness (item 9). Backs up: settlement (item 5) and refunds (item 6).
- Done: cash-outs (LED-15, 16) and payouts (17), with the cash-out-versus-hold race. Item 8 complete.
- Next: review item 9 as its owner: `EdgeCasesTest` and the invariants in `assertBooksBalance()`, written by Hersh on Sun 15 Nov.

**Md Hossain**
- Backs up: payments events (item 3). Refunds (item 6) moved to Hersh on Sat afternoon.

**Pierce**
- Owns: statement, desk balance and testing (item 7). Backs up: core and data (item 2).
- Done: the statement (LED-11), the desk balance (12), page security (18), the zero-sum property test (20, marked) and the marked-property map. Item 7 complete.
- Next: deploy from `docs/HANDOVER.md` alone on a clean machine, the check for item 4 step 7; report every question it leaves.

Status of each LED item and work item: [PHASES.md](PHASES.md), [CHECKLIST.md](CHECKLIST.md#work-items).

