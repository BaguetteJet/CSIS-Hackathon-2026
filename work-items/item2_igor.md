# Item 2: Core and data

**Owner:** Igor · **Backup:** Pierce · **LED:** 07, 20 (posting rules), 24 (migrations)

**Slice 1:** yes: schema, posting, `/me`.

- One posting service moves all money: movements sum to zero, students never go negative.
- `/me` shows the committed balance; restarts keep every balance.

**Builds on:** the starter. **Used by:** everyone (`post`, `lockStudent`, `lockStudents`).

### Steps

1. [x] **Schema, posting, `/me` (LED-07).** V3, `LedgerPostingService`, `LedgerAccounts`, `TopUpStore`, `LedgerCoreTest`.
   *Done when:* fresh migration, duplicate key, rollback and `/me` 401/403/zeros all pass.
2. [x] **`lockStudents`, `openRestaurant`.**
   *Done when:* 200 students lock in one call (`SettlementTest`).
3. [x] **Zero-sum support (LED-20).** Expose what item 7's property test needs.
   *Done when:* the test runs against the real posting service.
4. [x] **(stretch, deferred until LED-11 to 17 are done: bonus only counts once the core is complete) Daily check** at 02:00 Irish time: postings sum to 0, student `balance_cents` equals postings; log and metric, no event. Not Payments' daily reconciliation (ReconciliationMismatch): that one is theirs.
   *Done when:* a test moves the clock past 02:00 and the metric reads 0.

**Review:** a duplicate business ID moves no money; a rollback leaves postings and balances in agreement.
