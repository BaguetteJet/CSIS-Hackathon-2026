# 0003 Store the first hold answer per orderId

**Protects.** One orderId gets one answer (LED-04).

**Problem.** When Orders times out it asks again, maybe on our other instance, maybe with a different amount. A timeout doesn't mean we failed: we may already have committed (`lecture06a.pdf` pp. 4–12).

**Decision.** A hold first inserts a `hold_decision` row keyed by orderId, in the same transaction as the money and the event. A second call for that order waits for the row, then returns the saved answer. Refusals are saved too. A 503 saves nothing, so the retry decides fresh.

**Rejected.**
- The starter's `Idempotency-Key` table: the hold call has no such header, it keys on orderId in the body, and a repeat with a different amount would get 409 instead of the first answer.
- Redis: the answer and the money can't commit together, and it's one more thing to run.
- Trusting Orders not to retry: a retry after a hidden commit sets the money aside twice.
- Saving the answer in a second transaction: after a crash in between, the retry finds the money already set aside but no answer, and has to guess one.
- Saving only PLACED: a refused order retried after a top-up could get a different answer.

**Cost.** One row per order.

**Proof.** `HoldsTest`: a repeat on the other instance gets the first answer; the same order racing on both instances is decided once; 503 within 2 s leaves no answer behind.
