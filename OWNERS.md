# Owners

**Team lead:** Hersh (also integration: promotions, contracts, answers log).

We split the Ledger **by money flow**: each area owns one way money enters or leaves (the hold call, payments events, settlement, refunds...) with its own listener or API and its own tables. Each LED item then sits with one person instead of crossing three layers. Why, and the option we rejected: [decision 0001](docs/decisions/0001-work-split.md). Steps and dependencies: [work-items/](work-items/README.md). Progress: [PHASES.md](PHASES.md).

**Rules we keep** (handover §16):
- Every area has one owner and one backup.
- Everyone owns at least one area and backs up another.
- Every LED item has one owner, shown as the assignee of its Forgejo issue.

## Areas

| Area | Owner | Backup | LED items | Slice 1 | Item |
|---|---|---|---|---|---|
| Holds, integration | Hersh | Igor | 01, 03, 04, 18 (sign-in down), 22 (holds), 24 (SQL) | Yes: hold call | [1](work-items/item1_hersh.md) |
| Core and data | Igor | Pierce | 07, 20 (posting), 24 (migrations) | Yes: schema, posting, `/me` | [2](work-items/item2_igor.md) |
| Payments events, contracts | Sam | Member 6 | 02, 19 | Yes: top-ups | [3](work-items/item3_sam.md) |
| Operations | Shakeeba | Sam | 21, 22 (k6), 23, 24 (handover) | Yes: demo and evidence | [4](work-items/item4_shakeeba.md) |
| Settlement, releases | Hersh | Cian | 05, 06, 08, 09, 10 | No: golden path | [5](work-items/item5_settlement.md) |
| Refunds | Member 6 | Cian | 13, 14 | No | [6](work-items/item6_refunds.md) |
| Statement, desk, testing | Pierce | Shakeeba | 11, 12, 18 (pages), 20 (property test) | No | [7](work-items/item7_statement.md) |
| Cash-outs, payouts | Cian | Hersh | 15, 16, 17 | No | [8](work-items/item8_cashouts.md) |

## Each person

**Hersh** (team lead)
- Owns: holds (item 1), settlement and releases (item 5). Backs up: cash-outs (item 8).
- Done: the hold call (LED-03, 04), accounts on registration (01), releases and settlement (05, 06, 08, 09, 10), the load test (22), the sign-in 503 fix (18), the recompute SQL (24).
- Next: get the PRs reviewed and merged (Sat 11:00); promote into integration (Sat 16:30).

**Igor**
- Owns: core and data (item 2). Backs up: holds (item 1).
- Done: the schema, the posting service and `/me` (LED-07).
- Next: help item 7 with the zero-sum test (LED-20, by Sun 12:00); the daily check is a stretch goal.

**Sam**
- Owns: payments events and contract tests (item 3). Backs up: operations (item 4).
- Done: top-ups credited once (LED-02); zero-amount top-ups go to the dead-letter topic (19); the reset at 204 students.
- Next: listener stubs so item 8 can start (Sat 12:00); zero-amount cash-outs and payouts (19); contract tests for each new event.

**Shakeeba**
- Owns: operations (item 4). Backs up: statement and testing (item 7).
- Done: the slice-1 demo bench and its evidence.
- Next: tag `checkpoint-1` (Sat 11:00); dashboard (23); outage drills (21); the handover document (24, by Sun 16:00).

**Cian**
- Owns: cash-outs and payouts (item 8). Backs up: settlement (item 5) and refunds (item 6).
- Next: cash-outs (LED-15, 16) and payouts (17), by Sun 12:00.

**Member 6**
- Owns: refunds (item 6). Backs up: payments events (item 3).
- Next: refunds (LED-13) and refund limits and parking (14), by Sun 12:00. The stub they plug into already exists.

**Pierce**
- Owns: statement, desk balance and testing (item 7). Backs up: core and data (item 2).
- Next: the statement (LED-11), the desk balance (12), page security (18) and the zero-sum property test (20), by Sun 12:00.

## LED items

| Status | LED |
|---|---|
| Done | 01, 02, 03, 04, 05, 06, 07, 08, 09, 10, 22 |
| Partly done | 18, 19, 20, 23, 24 |
| To do | 11, 12, 13, 14, 15, 16, 17, 21 |

**Work items completed:** item 5 in full. Items 1–4 are partly done: their slice-1 steps are ticked; see each item's checkboxes.

**Still to set up:** one Forgejo issue per LED item, assigned to its owner above.
