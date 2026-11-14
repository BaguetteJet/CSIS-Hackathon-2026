# Everything merged, live on the fake line, Sat 14 Nov 2026

`main` at `de7d059` (#44 cash-outs and payouts, #45 desk, #46 refunds on top of #41 statement), tested as one tree before merging: 117 tests, 0 failed, 1 skipped (`ZeroSumPropertyTest` template). `./check` Ready. Fresh database `ledger_poc` (V1 to V9). 15:26 to 15:36 UTC.

| Step | We saw |
|---|---|
| Fund 204 students | student011 `availableCents` 0 → 2000 |
| One hold, three times, on 8081, 8082, 8081 | `PLACED` three times, one movement: 1400 available, 600 held |
| 12 students join a deal, clock +3 min | 12/12 `CONFIRMED`; settled 12, 6000, commission 600; a later hold `DEAL_CLOSED` |
| RefundApproved 200 on a charged order | `refund` POSTED, 20 commission reversed (restaurant −180) |
| Desk balance, Union finance | Same as the student's `/me` (1400 / 600); a student token gets 403 |
| WithdrawalRequested 500, then WithdrawalCompleted | RESERVED: available 900, held 1100, WithdrawalDebited; then PAID_OUT: held back to 600, WithdrawalPaidOut |
| Is the whole Ledger balanced? (HANDOVER §8) | All postings 0; no movement off 0; no drift; no negative student; nothing unsent |
| Grafana, dashboard "Ledger" | Every panel has data: holds by result and instance, latency, settlements, SettlementIssue 0 |

One hold on `ledger-2` answered 503 after 6 s (past its 1 s deadline) while the laptop's load average was 7 to 14 (three crash-looping pods of another project). To look into: a 503 should come within about 1 s.
