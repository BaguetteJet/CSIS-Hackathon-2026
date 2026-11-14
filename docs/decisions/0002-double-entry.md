# 0002 Double-entry accounts and postings

**Protects.** Every movement sums to zero (marked, LED-20). Finance can explain every euro (LED-24).

**Problem.** A balance on its own can't show where money came from or went.

**Decision.** A student has two accounts, AVAILABLE and HELD; a restaurant has PAYABLE; the Union has CASH and COMMISSION. Each movement is one `ledger_movement` row plus two or more `ledger_posting` rows, plus and minus, that sum to zero. A hold moves money from AVAILABLE to HELD.

**Rejected.**
- A balance column alone: no record of where money came from or went, so finance can't trace a euro (LED-24) and there's nothing to check sums to zero (LED-20).
- A held yes/no column on each order instead of a HELD account: every funds check must add up the student's open orders, and setting money aside leaves no posting on the statement.

**Cost.** 2 to 4 rows per movement.

**Proof.** All postings sum to 0 after every test in `LedgerCoreTest`, `ReleasesTest` and `SettlementTest`.
