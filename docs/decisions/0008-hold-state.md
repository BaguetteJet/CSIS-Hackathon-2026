# 0008 A hold ends once

**Context.** Withdrawal, abandonment, a failed deal and settlement can all reach one hold. Releasing and charging it would create money.

**Decision.** `hold_decision.state` (V6): OPEN, then RELEASED or CHARGED via `UPDATE ... WHERE state = 'OPEN'`. One updated row wins (`lecture12a-the-on-sale-1.pdf` pp. 13–14).

**Cost.** None beyond one column and an index.

**Check.** `HoldsTest` (OPEN on placing), `ReleasesTest`, `SettlementTest`.
