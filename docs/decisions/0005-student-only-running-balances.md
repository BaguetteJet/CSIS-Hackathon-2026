# 0005 Running balances for students only

**Protects.** Holds answer within 2 s at 100 a second, even during a settlement (LED-22).

**Problem.** Every top-up and settlement also changes the Union's CASH or COMMISSION account. When those had a balance column, every transaction locked the same Union row and waited in line: a hot row (`tutorial-nosql.pdf` pp. 3–9).

**Decision.** Only student accounts keep a `balance_cents` column. Union and restaurant balances are the sum of their postings. Nothing decides on them, so nothing needs them instantly.

**Rejected.**
- Several CASH rows to spread the locks: still locks, and a read still has to add the rows up. With no column there is nothing to lock.
- Union balances updated later from events: a second copy that can drift from the postings.

**Cost.** Reading a Union or restaurant balance adds up its postings; there's no API for them ([HANDOVER §10](../HANDOVER.md#10-known-limitations)); use the SQL in §8.

**Proof.** `LedgerCoreTest`: "top-ups for different students never wait on the Union cash account" fails on the old code. k6: holds max 666 ms during a 202-order settlement.
