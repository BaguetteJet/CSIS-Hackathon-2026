# 0007 Settle a deal in one local transaction

**Context.** A deal must settle exactly once, even through a crash (LED-06, marked), at up to about 300 orders.

**Decision.** One MySQL transaction:
1. claim `deal_settlement`;
2. charge the listed orders;
3. release the rest;
4. write `order_charge`, OrderCharged and DealSettled.

It's not a saga: every row is ours (`lecture12a-the-on-sale-1.pdf` pp. 6–11). The inbox stops a repeated envelope; `deal_settlement` stops a new envelope for the same deal.

**Cost.** The deal's students are locked until commit. The budget is under 1 s; measured about 0.8 s for 300 orders.

**Check.** `SettlementTest`: crash halfway charges nothing and the retry settles once; DealConfirmed twice settles once.
