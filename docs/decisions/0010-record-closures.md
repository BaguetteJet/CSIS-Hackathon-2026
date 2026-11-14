# 0010 Record closures so late holds are refused

**Protects.** A hold after its order or deal closed is refused (marked, LED-03).

**Problem.** Orders can ask for a hold after the student left or the deal ended: the call and the closing event race. A hold placed then would set money aside that nothing ever releases or charges.

**Decision.** The first closing event writes a row, in the same transaction as the release or settlement: `order_closure` (withdrawn, abandoned) or `deal_closure` (confirmed, failed, cancelled). The hold reads both before deciding and refuses ORDER_CLOSED or DEAL_CLOSED. With the lock order (0004), a race ends safe either way: hold first, the release then frees it; closure first, the hold is refused.

**Rejected.**
- Asking Orders or Deals over HTTP whether it's closed: a slow call inside the 2 s limit, and it fails when they're down.
- Comparing the time with the deal's deadline: the Ledger never refuses for time.
- Trusting Orders not to call after a close: the call and the event race.

**Cost.** Two small tables; a closure is recorded even when there was no hold.

**Proof.** `HoldsTest`: a hold for a withdrawn or abandoned order is ORDER_CLOSED; for a confirmed, failed or cancelled deal, DEAL_CLOSED. `ReleasesTest`: holds racing a failing deal all end refused or released.
