# 0007 Settle a deal in one local transaction

**Protects.** A deal settles exactly once, even through a crash (marked, LED-06).

**Problem.** A crash halfway through a deal at its cap (200 orders in the load test, PLT-14) must not leave some charged and some not, and a repeated DealConfirmed must not charge twice.

**Decision.** One MySQL transaction:
1. insert the deal's `deal_settlement` row (a second attempt finds it and stops);
2. charge the listed orders;
3. release the rest;
4. save the `order_charge` rows and the OrderCharged and DealSettled events.

A crash rolls all of it back, Kafka delivers the event again, and the retry settles once. The same event twice is skipped by the inbox (events already handled); a new event for the same deal is stopped by `deal_settlement`.

**Rejected.**
- A saga (one step at a time, undone on failure): meant for steps across services; these rows are all ours, and others would see half-settled deals (`lecture12a-the-on-sale-1.pdf` pp. 6–11).
- One transaction per order: after a crash some students are charged and others aren't until the retry finishes, and DealSettled needs extra tracking to know when the last order is done.
- A background job that settles confirmed deals later: it adds a delay and a second way for settlement to start. DealConfirmed already triggers it, and the inbox and `deal_settlement` already make it happen once.

**Cost.** The deal's students stay locked until it commits, under 1 s: 0.79 s for 202 orders during k6, 525 ms for 200 in `SettlementTest`.

**Proof.** `SettlementTest`: a crash halfway charges nothing and the retry settles the whole deal once; DealConfirmed again, as the same or a new event, settles once.
