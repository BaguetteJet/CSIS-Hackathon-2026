# 0003 Store the first hold answer per orderId

**Context.** Orders retries on timeout, maybe on the other instance, maybe with another body (LED-04). A timeout can hide a commit (`lecture06a.pdf` pp. 4–12).

**Decision.** Claim the orderId first by inserting `hold_decision` (primary key), in the same transaction as the money and the event. A second call waits on the key, then returns the stored answer. Refusals are stored too; a 503 stores nothing.

**Cost.** One row per order.

**Check.** `HoldsTest`: repeats, 8-way race on two instances, 503 within 2 s.
