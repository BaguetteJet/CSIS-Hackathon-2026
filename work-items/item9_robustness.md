# Item 9: Edge cases and robustness

**Owner:** Cian · **Backup:** Igor · **LED:** 06, 19, 20, 21 (with item 4), and every marked property

**Slice 1:** no: phase 4, the production bar.

- Every money path stays correct under repeats, disorder, races, bad input and a stopped instance: the conditions the marked properties are checked under (PHASES, 20 marks).
- Nothing new is trusted: each case is a failing test first, then the fix.

**Builds on:** items 1, 2, 3, 5 (built), 6 and 8 (as they land). **Used by:** item 4 (runbooks, alerts), item 7 (property test).

**Files:** `test/.../ledger/EdgeCasesTest.kt`, `test/.../ledger/LedgerInvariants.kt`, this item's section of `docs/HANDOVER.md`. A fix in another area's file goes in a PR reviewed by that area's owner.

### Invariants

Checked after every container test (step 1), and by SQL on a live deployment.

| # | Must always hold |
|---|---|
| I1 | All postings sum to 0; every movement sums to 0 |
| I2 | Every student balance equals the sum of its postings, and is never negative |
| I3 | A student's HELD equals the sum of their OPEN holds |
| I4 | Each hold has one HOLD movement, and at most one of RELEASE or ORDER_CHARGE, matching its state |
| I5 | `deal_settlement` totals equal the sum of the deal's `order_charge` rows |
| I6 | Every committed event is published once (no row left unsent after a minute) |

### Steps

1. [ ] **`LedgerInvariants` (I1 to I6).** One `assertConsistent()` for tests; replaces `assertBooksBalance()` as each test is touched. The same checks as SQL in HANDOVER §8.
   *Done when:* every existing container test calls it and passes.
2. [ ] **Bad events (LED-19).** Outside the contract schema, so straight to `ledger.dlt` as an `InvalidEvent`, like a zero top-up: `DealConfirmed` with `priceCents` < 0 or `commissionBasisPoints` outside 0 to 10000. Today `Money.commission` throws mid-settlement: nothing moves, but the event is retried for 2.5 minutes first, holding up its partition. Inside the schema but odd, so reported as SettlementIssue: one `orderId` listed twice with different students (today the second is dropped silently); zero orders.
   *Done when:* each case has a test, the books are unchanged, and a bad event reaches `ledger.dlt` without retries.
3. [ ] **Conflicting repeats.** Same business key, different body: a hold for the same `orderId` with another amount or student; a top-up with the same `topUpId` and another amount; `DealConfirmed` again with another price or list. The first wins (decision 0003).
   *Done when:* nothing moves, and a warning log and `ledger.conflicting_repeats` counter make it visible.
4. [ ] **Races, two instances.** Withdrawn vs settlement on one order; DealFailed vs DealConfirmed; a hold vs DealCancelled; a top-up vs a hold for exactly the balance; a cash-out vs a hold (with item 8).
   *Done when:* each has exactly one outcome and I1 to I6 hold, 50 runs in a row.
5. [ ] **Out of order.** OrderWithdrawn before its hold (the hold is ORDER_CLOSED); DealFailed after DealConfirmed (nothing moves); DealConfirmed after DealFailed (nothing charged, issues reported); refund before its charge (parked, item 6).
   *Done when:* each has a test.
6. [ ] **Crash and redelivery.** Settlement is done (item 5). Add: top-up and release rolled back halfway, then redelivered; an instance stopped after commit but before publishing (the other instance's outbox sends it).
   *Done when:* nothing doubles and nothing is lost.
7. [ ] **Limits.** 1 cent; commission rounding at 1 cent and at 10000 bps; price 0; a balance near the top of BIGINT (a clear failure to `ledger.dlt`, not a retry loop).
   *Done when:* each behaves as a table in this file says.
8. [ ] **Stuck money.** OPEN holds on a closed deal (left by a SettlementIssue) and records in `ledger.dlt`: a HANDOVER §8 query and a metric for item 4's 6 h alert.
   *Done when:* the query lists a hold left by a live SettlementIssue.
9. [ ] **Settlement speed margin (with Hersh, item 5).** 300 orders took 1087 and 1212 ms on a busy laptop on Sat 14 Nov (limit 1 s; 800 ms recorded earlier). Find the slow part before the deploy gate.
   *Done when:* under 1 s with the dev kit running, three runs in a row.

**If short of time:** steps 1, 2 and 4 first; they defend the marked properties directly.
