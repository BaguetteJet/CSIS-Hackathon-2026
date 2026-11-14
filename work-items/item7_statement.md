# Item 7: Statement, desk and testing

**Owner:** Member 7 · **Backup:** Shakeeba · **LED:** 11, 12, 18 (page routes), 20 (property test)

- Students see every change to their balance in their own words; the desk sees any student's balance.
- One property test proves the books always balance.

**Builds on:** postings (item 2); statement entries from items 1, 3, 5, 6, 8.

### Steps

1. [ ] **Statement (LED-11):** `GET /api/ledger/me/entries`. AVAILABLE postings with an entry kind, newest first, at most 100.
   *Done when:* credits minus debits equal availableCents.
2. [ ] **Wording.** Student words only ("set aside", "Account").
   *Done when:* a test fails on "hold" or "wallet".
3. [ ] **Desk balance (LED-12):** `GET /api/ledger/students/{id}`. UNION_FINANCE only; malformed is 404; unknown gives zeros.
4. [ ] **Security (LED-18).** 401 before 403 on statement and desk.
5. [ ] **Zero-sum property test (LED-20, marked).** `ZeroSumPropertyTest` over the real services, with random operations.
   *Done when:* 200 runs pass and a failure prints its seed.
6. [ ] **Marked-property map** in `docs/HANDOVER.md` §6.
