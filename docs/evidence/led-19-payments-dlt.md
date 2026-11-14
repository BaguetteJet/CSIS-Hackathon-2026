# LED-19: zero-amount payments events to the dead-letter topic

Sat 14 Nov, dev kit, both replicas from `./check`. Four contract samples with fresh eventIds, put on `payments.v1` with `kafka-console-producer`, in this order:

| Event | Amount | Key | Result |
|---|---|---|---|
| WithdrawalRequested `0fdb95fa…` | 0 | student `89a653fe…` | On `ledger.dlt` at once, no retries; not in `processed_event` |
| PayoutConfirmed `ae801bd4…` | 0 | restaurant `fdd58bad…` | On `ledger.dlt` at once, no retries; not in `processed_event` |
| WithdrawalRequested `d038bbf3…` | 1000 | student `89a653fe…` | Reached the `NoWithdrawalsYet` stub (logged); processed, nothing moved |
| TopUpRecorded `703e61cc…` | 700 | student `89a653fe…` | Credited: 700 available, 0 held. The consumer moved on past the bad event on its key |

Books afterwards (HANDOVER §8): all postings 0, movements not summing to 0: 0, student balances not matching postings: 0.

Tests: `ContractTest` (routing, replays, zero amounts, every event we publish), `PaymentsDeadLetterTest` (the same through real Kafka and MySQL).
