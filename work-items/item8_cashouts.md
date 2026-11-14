# Item 8: Cash-outs and payouts

**Owner:** Cian · **Backup:** Hersh · **LED:** 15, 16, 17

**Slice 1:** no: phase 3.

- A cash-out is reserved before cash leaves, then paid out or returned, never both.
- Each restaurant payout is taken from its payable once.

**Builds on:** item 3 (listener stubs), item 2. **Used by:** item 7 (WITHDRAWAL entries).

### Steps

1. [x] **Migration:** `withdrawal(withdrawal_id PK, student_id, amount_cents, state, decided_at)`; state RESERVED, REFUSED, PAID_OUT or RETURNED. REFUSED is kept so a repeat gets the same answer ([schema §7](../docs/SCHEMA.md#7-fit-with-the-contracts)).
2. [x] **Reserve (LED-15).** Claim the row, lock the student, AVAILABLE → HELD, then WithdrawalDebited; otherwise WithdrawalRefused.
   *Done when:* a cash-out racing a hold has one winner.
3. [x] **Pay out or return (LED-16).** Conditional update from RESERVED. Completed: HELD → Union cash. Failed: HELD → AVAILABLE. First one wins; the reserved amount wins, and a different amount on the event is logged.
4. [x] **Payouts (LED-17).** PAYOUT by payoutId: payable → Union cash; may go negative.
   *Done when:* the same payoutId twice debits once.
5. [x] **Contract tests** for Withdrawal* and PayoutDebited.
