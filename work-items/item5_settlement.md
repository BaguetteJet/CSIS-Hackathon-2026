# Item 5: Settlement and releases

**Owner:** Hersh · **Backup:** Member 5 · **LED:** 05, 06, 08, 09, 10

- A confirmed deal charges each listed order the tier price once: commission to the Union, the rest to the restaurant, leftover back.
- An order or deal that doesn't go ahead returns every cent; problems are reported, not hidden.

**Builds on:** item 1 (hooks, hold state), item 2 (`lockStudents`). **Used by:** item 6 (`order_charge`, wake-ups).

### Steps

1. [x] **`V7__settlement.sql`:** `order_charge`, `deal_settlement`.
2. [x] **Releases (LED-08, 09).** `Releases.kt`: hold row first, then the closure row, then students.
   *Done when:* one HoldReleased per hold; a hold placed just before OrderAbandoned is released.
3. [x] **Settlement (LED-05).** `Settlement.kt`, one transaction.
   *Done when:* the worked example is exact: 5000, 600, 475 at 10% gives 48 / 427 / 125 and 4525 / 0.
4. [x] **Problems (LED-10).** SettlementIssue for no hold, released, other deal, other student, below price.
   *Done when:* the rest settles and DealSettled excludes them.
5. [x] **Exactly once (LED-06, marked).**
   *Done when:* a crash halfway charges nothing and the retry settles once; DealConfirmed twice settles once.
6. [x] **Speed.** 300 orders settle in under 1 s (measured 0.8 s).

**Review:** live on the fake line ([evidence](../docs/evidence/item5-live.md)).
