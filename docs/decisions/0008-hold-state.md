# 0008 A hold ends once

**Context.** A withdrawal, an abandoned order, a failed deal and settlement can all reach the same hold. Released and charged would give the money back and pay the restaurant: money from nowhere.

**Decision.** Each hold has a `state` (V6): OPEN, then RELEASED or CHARGED. Ending it is `UPDATE ... WHERE state = 'OPEN'`: only the first update changes the row, and only that one moves money (`lecture12a-the-on-sale-1.pdf` pp. 13–14).

**Rejected.**
- A yes/no released column: can't tell released from charged, and a second "charged" column would let both be set.
- Deleting the row: loses the saved answer that retries need (0003).
- Working it out from the postings: RELEASE and ORDER_CHARGE movements have different keys, so nothing stops both being posted for one order.

**Cost.** One column and an index.

**Check.** `HoldsTest` (new holds are OPEN), `ReleasesTest`, `SettlementTest`.
