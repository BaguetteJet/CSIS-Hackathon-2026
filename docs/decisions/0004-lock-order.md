# 0004 One lock order for every money change

**Protects.** No balance goes below zero (marked, LED-20). No deadlocks.

**Problem.** A hold, a top-up, a release and a settlement can hit the same student at once on our two instances. Two transactions that lock the same rows in opposite orders wait for each other, and MySQL rolls one back (a deadlock).

**Decision.** Every money change locks in this order:
1. the hold, closure and settlement rows it needs;
2. the students' accounts, all at once, lowest id first;
3. the outbox (events waiting to be sent), by publishing last.

Check a balance only after step 2, with that locked read. A plain SELECT can return an old balance.

**Rejected.**
- Lock rows as needed and rely on retrying deadlocks: deadlocks become routine under load, and a settlement rolled back redoes up to 0.8 s of work. The hold call still retries a deadlock within 1 s, but only as a backstop.
- Optimistic locking (retry when someone else changed the row): one top-up to any student in a 200-order deal makes the whole settlement start again.
- One lock for everything: every hold waits up to 0.8 s whenever any deal settles.

**Cost.** A release must lock the hold before it writes the closure row.

**Proof.** `HoldsTest`: two €6 orders racing for €10 on both instances, one placed and one refused. `ReleasesTest`: holds racing a failing deal. The k6 load test during a settlement.
