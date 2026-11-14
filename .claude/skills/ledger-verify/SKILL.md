---
name: ledger-verify
description: Prove a Ledger change works before a PR, with tests, ./check and a live run on the dev kit's fake line. Use after any code change.
---

# Verify a change

With JDK 21. Each step must pass before the next (the `verification-before-completion` skill: evidence before claims).

1. **Tests:** `./gradlew test contractTest containerTest`. All green.
2. **Two instances:** `./check`. It must say Ready.
3. **Live, on the fake line** (dev kit checkout; `FAKE_EXCLUDE=ledger`):
   - fund students: `./sim money-in --students 204`;
   - students join through the gateway, so the fake Orders calls our hold: `curl -X POST -H "Authorization: Bearer $(./token student011)" localhost:8080/api/orders/deals/<dealId>/orders`;
   - the hold call directly: `POST localhost:8081/internal/ledger/holds` and `:8082`;
   - drive the rest: `./sim deal-opens --cutoff 2`, `./clock advance 3m`, `./sim deal-fails`, `--repeat` for duplicates;
   - always finish with `./clock reset`.
4. **Books:** run the "Is the whole Ledger balanced?" SQL in `docs/HANDOVER.md` §8. All three rows must read 0.
5. **Evidence:** for anything a checkpoint will ask about, add IDs and numbers to `docs/evidence/`. Never include tokens.

*Done when* all five pass. Paste the outputs into the PR.
