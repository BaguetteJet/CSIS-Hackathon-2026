# Item 9: Edge cases and robustness

**Owner:** Cian · **Backup:** Igor · **LED:** 06, 19, 20, 21 (with item 4), and every marked property

**Slice 1:** no: phase 4, the production bar.

- The marked properties hold under repeats, disorder, races and a stopped instance: the conditions they're checked under (PHASES, 20 marks).
- Nothing new is trusted: each case is a failing test first, then the fix.
- Only what the spec asks for. Item 8 comes first: this item takes what time is left.

**Builds on:** items 1, 2, 3, 5 (built), 6 and 8 (as they land). **Used by:** item 4 (runbooks), item 7 (property test).

**Files:** `test/.../ledger/EdgeCasesTest.kt`, `assertBooksBalance()` in `test/.../ledger/LedgerFlowTest.kt` (planned as `LedgerInvariants.kt`), this item's section of `docs/HANDOVER.md`. A fix in another area's file goes in a PR reviewed by that area's owner.

### Invariants

Checked after every container test (step 1), and by SQL on a live deployment.

| # | Must always hold |
|---|---|
| I1 | All postings sum to 0; every movement sums to 0 |
| I2 | Every student balance equals the sum of its postings, and is never negative |
| I3 | A student's HELD equals their OPEN holds plus their RESERVED cash-outs (item 8) |
| I4 | Each hold has one HOLD movement, and at most one of RELEASE or ORDER_CHARGE, matching its state |
| I5 | `deal_settlement` totals equal the sum of the deal's `order_charge` rows |
| I6 | Every committed event is published once (no row left unsent after a minute) |

### Steps

1. [x] **`LedgerInvariants` (I1 to I6).** One `assertConsistent()` for tests; replaces `assertBooksBalance()` as each test is touched. The same checks as SQL in HANDOVER §8.
   *Done when:* every existing container test calls it and passes.
   *Done:* I1 to I5 inside `LedgerFlowTest.assertBooksBalance()` (I1, I2 are the daily check's own), and every container test that moves money calls it (`DeskTest`, `StatementTest`, `ZeroSumPropertyTest` added Sun 15 Nov; not `LedgerMetricsTest`, which breaks a balance on purpose); each was seen to fail on a deliberate break. As SQL: I1, I2, I5 and stuck money in HANDOVER §8. I6 (published within a minute) is the outbox's, not checked here.
2. [x] **Races, two instances.** Withdrawn vs settlement on one order; DealFailed vs DealConfirmed; a hold vs DealCancelled; a top-up vs a hold for exactly the balance. Item 8 owns the cash-out vs hold race (LED-15).
   *Done when:* each has exactly one outcome and I1 to I6 hold, 50 runs in a row.
   *Done:* `EdgeCasesTest` D8, D20, T7 (5 to 10 runs each, so the suite stays quick: two transactions on separate connections, as two instances are); a hold vs a closing deal is `ReleasesTest`'s race (DealFailed and DealCancelled share `dealClosed`).
3. [x] **Out of order.** OrderWithdrawn before its hold (ORDER_CLOSED); DealFailed after DealConfirmed (nothing moves); DealConfirmed after DealFailed (nothing charged, issues reported). Item 6 owns refund before charge.
   *Done when:* each has a test.
   *Done:* `HoldsTest` (ORDER_CLOSED), `EdgeCasesTest` D6, D7.
4. [x] **Commission rounding** (the money check reads it): 1 cent, a half cent rounding up, 0 and 10000 basis points.
   *Done when:* each matches `Money.commission`.
   *Done:* `EdgeCasesTest` X18, through real settlement.

**If short of time:** steps 1 and 2; they defend the marked properties directly.

**After the steps** (not required by the spec; [EDGE-CASES](../docs/EDGE-CASES.md#cases-that-cause-issues)): Q1, a repeat orderId for another student, is the one 🔴 left (items 1 and 5); X11, a deadlock test, and X16, a log line for unknown events, are still open.

### Out of scope

Dropped on Sat 14 Nov to keep to the spec:

| Was | Why not |
|---|---|
| Settlement under 1 s at 300 orders | The biggest test deal is 200 (PLT-14), already under 1 s; Orders retries a slow hold (ORD-13) |
| Stuck-hold metric and alert | Platform's (PLT-11), from our HoldPlaced, OrderCharged and HoldReleased |
| Counter and log for conflicting repeats | The spec only needs the first answer, already tested |
| Balances near the BIGINT limit, price 0 | Not in the spec; Payments caps top-ups at €200 |
| Crash-before-publish tests | The starter's outbox; item 4's drills cover LED-21 |
| DealConfirmed outside its schema to `ledger.dlt` | The contract forbids it, so Deals can't send it |
| SettlementIssue for one `orderId` listed twice with different students, or for zero orders | Inside the schema, but no test needs it; listed as a known limitation (HANDOVER §10) |
