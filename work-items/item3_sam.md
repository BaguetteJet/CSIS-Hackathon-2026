# Item 3: Payments events and contracts

**Owner:** Sam · **Backup:** Md Hossain · **LED:** 02, 19, contract tests

**Slice 1:** yes: top-ups.

- Every desk top-up is credited once, at its first amount.
- Broken events go straight to the dead-letter topic; every event we publish matches the contracts, in CI.

**Builds on:** item 2. **Used by:** item 8 (payments listener), everyone (contract tests).

### Steps

1. [x] **Top-ups (LED-02).** `PaymentsConsumer`: TopUpRecorded, then TopUpCredited.
   *Done when:* a replayed envelope and a new envelope with the same topUpId give one credit.
2. [x] **Reset at full size.** `./sim money-in --students 204`.
   *Done when:* 204 credited once, postings sum to 0.
3. [x] **Room for item 8.** Route Withdrawal* and PayoutConfirmed to `Withdrawals` / `Payouts` stubs.
   *Done when:* item 8 fills them without touching `PaymentsConsumer.kt`.
4. [x] **Bad events (LED-19).** Zero-amount WithdrawalRequested and PayoutConfirmed throw `InvalidEvent`.
   *Done when:* each lands on `ledger.dlt`.
5. [x] **Contract tests** for each new event from items 6 and 8.
   *Done when:* every event in the team document's publish list is covered.

**Review:** `./gradlew contractTest` green in CI.
