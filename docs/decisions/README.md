# Decision records

Each record says what it protects, the problem, what we chose, what we rejected and why, what it costs, and the test that proves it.

| # | Decision | Protects | Proof |
|---|---|---|---|
| [0001](0001-work-split.md) | Split the work by money flow | One owner per LED item | OWNERS.md, issues #7–#30 |
| [0002](0002-double-entry.md) | Double-entry accounts and postings | Movements sum to zero | `LedgerCoreTest`, `ReleasesTest`, `SettlementTest` |
| [0003](0003-first-answer-per-order.md) | Store the first hold answer per orderId | One answer per order | `HoldsTest` |
| [0004](0004-lock-order.md) | One lock order for every money change | No negative balance, no deadlocks | `HoldsTest`, `ReleasesTest` races |
| [0005](0005-student-only-running-balances.md) | Running balances for students only | Holds within 2 s under load | `LedgerCoreTest`, k6 |
| [0006](0006-one-listener-per-topic.md) | One Kafka listener per topic | No event missed | `ConsumerGroupCheckTest` |
| [0007](0007-settlement-one-transaction.md) | Settle a deal in one local transaction | A deal settles once | `SettlementTest` |
| [0008](0008-hold-state.md) | A hold ends once | Never released and charged | `ReleasesTest`, `SettlementTest` |
| [0009](0009-early-refunds-parked.md) | Park early refunds | No over-refund | `RefundsTest`; wake-ups: item 6 |
| [0010](0010-record-closures.md) | Record closures so late holds are refused | Late holds refused | `HoldsTest`, `ReleasesTest` |

## Marked properties

| Property (5 marks each) | Kept by | Proof |
|---|---|---|
| Movements sum to zero; no negative balance | 0002, 0004 | Zero-sum after every flow test; race tests. Property test: item 7 |
| A deal settles exactly once, through a crash | 0007 | `SettlementTest` crash and repeat tests |
| No order refunded more than charged | 0009 | `RefundsTest` over-the-charge and part-refund tests |
| A hold after its order or deal closed is refused | 0010, 0004 | `HoldsTest` closed refusals |

Status of each: [PHASES.md](../../PHASES.md).
