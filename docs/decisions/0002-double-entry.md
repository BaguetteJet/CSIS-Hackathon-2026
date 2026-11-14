# 0002 Double-entry accounts and postings

**Context.** Finance must prove every euro (LED-24); every movement must sum to zero (LED-20).

**Decision.** Students have AVAILABLE and HELD, restaurants PAYABLE, the Union CASH and COMMISSION. A movement is one `ledger_movement` row plus signed `ledger_posting` rows that sum to zero. Setting money aside moves it from AVAILABLE to HELD; it's not a flag.

**Rejected.**
- Balance column only: no record of how it got there; nothing to sum (LED-20, LED-24).
- `held` flag per order: every funds check becomes a SUM.

**Cost.** 2 to 4 rows per movement.

**Check.** All postings sum to 0 (`LedgerCoreTest`, `LedgerTopUpTest`, `LedgerFlowTest`).
