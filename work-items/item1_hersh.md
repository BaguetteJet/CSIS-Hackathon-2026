- **Owner: Hersh · Backup: Igor**

### What this means

- When someone places an order, Ledger sets the money aside so they cannot spend it on another order.
- If there is too little available, Ledger refuses the hold and tells Orders how much is available.
- If Orders retries, either Ledger instance can return the original answer without setting aside money twice.

### Checklist

- Build `POST /internal/ledger/holds` (LED-03/04).
- Use Igor’s posting operation to reserve funds. Return `PLACED` or `INSUFFICIENT_FUNDS`.
- Save the first answer for each `orderId`, including refusals. Record the corresponding event in the same transaction.
- Check that a replay through the other replica returns the same answer without moving money or publishing again.

### Implementation

- Use `contracts/openapi/ledger-internal.yaml` from contracts 1.3.0. The request has `orderId`, `studentId`, `dealId`, UTC `cutoffAt`, and positive whole `amountCents`. The hold route is internal. Call it directly on a Ledger replica for tests.
- Coordinate the durable `orderId` record with Igor. Check or claim it atomically so two replicas cannot both make the first decision. A valid retry with a changed amount, student, or deal returns the stored first answer. It creates no new movement or outbox entry.
- For a new order, use Igor’s operation to lock the student balance and decide the hold. Return HTTP 200 `PLACED` for a reservation, or HTTP 200 `INSUFFICIENT_FUNDS` with the available cents at decision time. A missing account has zero available. Save the result and stage `HoldPlaced` or `HoldRejected` in the same transaction as any movement. Both events carry the balance after the decision.
- Validate the request at the API boundary. Malformed input is 422 with field errors, and a body over 64 KB is 413. Follow the contract’s 503 for temporary service or dependency failure. Orders allows two seconds for the call. `cutoffAt` does not itself close an order. When closure is added later, check a saved decision first and use `ORDER_CLOSED` before `DEAL_CLOSED` for new requests.

### Ready to review

- Check exact funds, one cent short, and an unknown student. Run concurrent requests for the same order against different replicas and verify one decision. With €10 available, race two distinct €6 orders and verify one reservation, one refusal, €4 available, and €6 held.
