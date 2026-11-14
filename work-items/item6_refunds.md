# Item 6: Refunds

**Owner:** Md Hossain · **Backup:** Cian · **LED:** 13, 14

**Slice 1:** no: phase 3.

- A refund goes back to the student, reversing commission and the restaurant's share in proportion.
- No order is refunded more than it was charged; an early refund waits for its charge.

**Builds on:** item 5 (`order_charge`, `RefundWakeups`). **Used by:** item 7 (REFUND entries).

### Steps

1. [x] **Stub.** `RefundWakeups.kt` with no-op `NoParkedRefunds` (added by item 5); replace it.
2. [x] **Migration:** `V8__refund.sql`: `refund(refund_id PK, order_id, deal_id, student_id, amount_cents, commission_reversed_cents, reason, outcome, decided_at)`. Deal and student come from RefundApproved: RefundRefused needs them even with no charge ([schema §7](../docs/schema.md#7-fit-with-the-contracts)).
3. [x] **`SupportConsumer`** on support.v1: claim the refundId inside `inbox.once`.
   *Done when:* the same refundId twice posts once.
4. [x] **Post (LED-13).** Lock `order_charge`, then the student. Over the charge: EXCEEDS_CHARGE. Otherwise reverse commission with `Money.refundReversal` and post REFUND.
   *Done when:* 450 / 45 refunded 5 then 445 reverses 1 then 44.
5. [ ] **Park and wake (LED-14).** Park by order or deal; `orderCharged` posts, `holdReleased` and `dealDecided` refuse NEVER_CHARGED.
   *Done when:* tests cover refund before charge, then release, and with no hold.
6. [ ] **Metrics:** parked count and oldest age (alert at 6 h).

**Review:** refunds over the charge are refused (marked property).
