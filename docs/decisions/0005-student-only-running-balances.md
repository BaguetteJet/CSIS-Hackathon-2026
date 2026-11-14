# 0005 Running balances for students only

**Context.** Locking every account's balance made Union CASH and COMMISSION hot rows: every top-up and settlement queued on them (`tutorial-nosql.pdf` pp. 3–9).

**Decision.** Only student accounts keep `balance_cents`. Union and restaurant balances are the sum of their postings; no API or event needs them live (LED-24 known limitation).

**Rejected.**
- Balances on every account: hot rows on Union CASH and COMMISSION.
- Sharded CASH rows: complexity not needed at 100/s.
- Union balances updated from events: a second truth that can drift.

**Cost.** Reading a payable is a SUM. The zero-sum check sums postings.

**Check.** `LedgerCoreTest`: "top-ups never wait on the Union cash account" fails on the old code.
