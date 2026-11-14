# 0004 One lock order for every money change

**Context.** Holds, top-ups, releases and settlement can hit one student at once on two instances. No balance may go negative (LED-20).

**Decision.** Lock in this order:
1. business rows (holds by order_id, then closure and claim rows);
2. student accounts, all at once, ascending id;
3. the outbox counter, by publishing last.

Decide only after step 2. Read every balance an event carries under that lock. A plain SELECT under REPEATABLE READ can be stale.

**Rejected.**
- Lock as needed, retry deadlocks: retries pile up under load; the 2 s budget breaks.
- Optimistic locking: unbounded retries on a busy student.
- One coarse lock: every hold waits on every settlement.

**Cost.** A release must lock the hold row before writing its closure row.

**Check.** `HoldsTest` and `ReleasesTest` races; k6 during settlement.
