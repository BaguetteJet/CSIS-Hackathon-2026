# Item 5: releases and settlement on the fake line

Sat 14 Nov, dev kit with `FAKE_EXCLUDE=ledger`. Students joined through the gateway, so the fake Orders made real hold calls.

| Scenario | Result |
|---|---|
| **Golden path.** 12 join, 1 leaves, `./clock advance 3m`; DealTipped (500, 11), DealConfirmed | 11 charged at 500 (50 commission, 100 back), 1 released WITHDRAWN; DealSettled 11 / 5500 / 550; payable 4950 |
| **Failed deal.** 10 join, 1 leaves; 9 is below the lowest tier | DealFailed: 9 released DEAL_FAILED, 1 WITHDRAWN |
| **Foreign list.** `./sim deal-confirmed --repeat` lists 32 orders with no hold | 32 SettlementIssue, 7 released NOT_ON_LIST, settled once |
| **Cap under load.** 202 orders while k6 sends 100 holds/s | Settled in 786 ms; holds max 666 ms ([LED-22](led-22-holds-k6.md)) |

After every run: every student's held balance equals their open holds (205 / 205), none negative, nothing unsent, postings sum to 0.
