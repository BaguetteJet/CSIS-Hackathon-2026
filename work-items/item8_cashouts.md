# Item 8: Cash-outs and payouts

**Owner:** Member 5 · **Backup:** Sam · **LED:** 15, 16, 17

- A cash-out is reserved before cash leaves, then paid out or returned, never both.
- Each restaurant payout is taken from its payable once.

**Builds on:** item 3 (listener stubs), item 2. **Used by:** item 7 (WITHDRAWAL entries).

### Steps

1. [ ] **Migration:** `withdrawal(withdrawal_id PK, student_id, amount, state)`.
2. [ ] **Reserve (LED-15).** Claim the row, lock the student, AVAILABLE → HELD, then WithdrawalDebited; otherwise WithdrawalRefused.
   *Done when:* a cash-out racing a hold has one winner.
3. [ ] **Pay out or return (LED-16).** Conditional update from RESERVED. Completed: HELD → Union cash. Failed: HELD → AVAILABLE. First one wins; the reserved amount wins.
4. [ ] **Payouts (LED-17).** PAYOUT by payoutId: payable → Union cash; may go negative.
   *Done when:* the same payoutId twice debits once.
5. [ ] **Contract tests** for Withdrawal* and PayoutDebited.
