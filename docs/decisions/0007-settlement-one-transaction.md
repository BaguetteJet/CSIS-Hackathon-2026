# 0007 Settle a deal in one local transaction

**Context.** A deal must settle exactly once, even if an instance crashes halfway (LED-06, marked). A deal can have about 300 orders.

**Decision.** One MySQL transaction:
1. insert the deal's `deal_settlement` row (a second attempt finds it and stops);
2. charge the listed orders;
3. release the rest;
4. save the `order_charge` rows and the OrderCharged and DealSettled events.

A crash rolls all of it back, Kafka delivers the event again, and the retry settles once. The same event twice is skipped by the inbox (events already handled); a new event for the same deal is stopped by `deal_settlement`.

**Rejected.**
- A saga (one step at a time, undone on failure): meant for steps across services; these rows are all ours, and others would see half-settled deals.
- One transaction per order: after a crash some students are charged and others aren't until the retry finishes, and DealSettled needs extra tracking to know when the last order is done.
- A background job that settles confirmed deals later: it adds a delay and a second way for settlement to start. DealConfirmed already triggers it, and the inbox and `deal_settlement` already make it happen once.

**Cost.** The deal's students stay locked until it commits: under 1 s, measured about 0.8 s for 300 orders.

**Check.** `SettlementTest`: a crash halfway charges nothing and the retry settles once; DealConfirmed twice settles once.
