# Ledger architecture

The Ledger decides one thing: whether a student's money is available. Every movement of money is a set of postings that sum to zero. Structured by the module's seven steps; decisions in [decisions/](decisions/README.md), progress in [PHASES.md](../PHASES.md). Words: [GLOSSARY.md](../GLOSSARY.md). What we own, what we need from each team and what they need from us: [NEIGHBOURS.md](NEIGHBOURS.md).

## 1. Requirements

| Caller | Operation | Must |
|---|---|---|
| Orders | Hold call | Answer within 2 s; one answer per orderId on either instance |
| Payments | Top-ups, cash-outs, payouts | Apply each business ID once |
| Deals | Confirmed / failed / cancelled | Settle exactly once, even through a crash |
| Support | Refunds | Never exceed the charge; wait if early |
| Student, Union finance | `/me`, entries, desk balance | Show the committed balance |
| Platform | UserRegistered; metrics and events for its alerts | Open each student's accounts once; publish every event once |

**Invariants** (each one a test can break):
1. Every movement sums to zero.
2. No student balance is ever below zero.
3. One orderId gets one hold answer.
4. A hold for a closed order or deal is refused.
5. A hold ends once: released or charged.
6. A deal settles exactly once.
7. No order is refunded more than it was charged.
8. Each student's event balances chain in sequence order.

## 2. Estimates

| Load | Number |
|---|---|
| Hold calls at peak | 100/s for 20 s (LED-22) |
| Largest deal | 200 orders in the load test (PLT-14), about 1,400 rows in one transaction |
| Students funded in integration | 204 |
| Memory per instance | 512 MB limit, about 330 MB used |

The load is small. The risks are **contention** (hot rows, long locks) and **correctness under retries**, not throughput.

## 3. API and events

| Hold answer | HTTP | When |
|---|---|---|
| PLACED / INSUFFICIENT_FUNDS | 200 | Enough money, or not (with `availableCents`) |
| ORDER_CLOSED / DEAL_CLOSED | 200 | A closing event was already processed |
| VALIDATION_FAILED / PAYLOAD_TOO_LARGE | 422 / 413 | Bad input / over 64 KB |
| UNAVAILABLE | 503 | Database trouble: nothing saved, so a retry decides afresh |

A timeout after commit is safe: Orders retries the same orderId and gets the stored answer (`lecture06a.pdf` pp. 4–12). Page routes take the student ID from the token only (`lecture09b.pdf` pp. 4–11). If the sign-in key can't be fetched, they answer 503.

| Topic | One listener | Effect |
|---|---|---|
| payments.v1 | `PaymentsConsumer` | Credit top-ups; cash-outs (`Withdrawals`), payouts (`Payouts`) |
| orders.v1, deals.v1 | `ClosuresConsumer` | Close; release; settle; open payables |
| users.v1 | `KnownUsersConsumer` (starter, `example/`) | Open student accounts |
| support.v1 | `SupportConsumer` | Refunds: posted, refused or parked; the rest of support.v1 is ignored |
| ledger.v1 | Outbox relay (starter) | Every Ledger event, balance after it; keyed by student, restaurant or deal ([NEIGHBOURS §4](NEIGHBOURS.md#4-event-keys)) |

## 4. Data

| Table | Key | Source of truth for |
|---|---|---|
| `ledger_account` | owner + purpose | Accounts; `balance_cents` kept for **students only** |
| `ledger_movement` | (type, reference id) | Whether an effect happened (top-up, hold, charge...) |
| `ledger_posting` | id | Every balance, recomputable |
| `hold_decision` | order_id | First hold answer; `state` OPEN → RELEASED or CHARGED |
| `order_closure`, `deal_closure` | order_id, deal_id | Refusing late holds |
| `order_charge`, `deal_settlement` | order_id, deal_id | What was charged; that a deal settled |
| `refund` | refund_id | Each refund's outcome (item 6) |
| `withdrawal` | withdrawal_id | A cash-out's reserved amount and state: RESERVED, REFUSED, PAID_OUT or RETURNED |
| starter inbox, outbox, parking | event id | Once-only consumption, publishing, early events |

## 5. Design

```mermaid
flowchart LR
  Orders -->|hold call| H[Holds]
  Gateway -->|/api/ledger| A[LedgerAccounts]
  Kafka[(Kafka)] --> PC[PaymentsConsumer] & CC[ClosuresConsumer] & KU[KnownUsersConsumer] & SC[SupportConsumer]
  SC --> RF[Refunds]
  CC --> R[Releases] & S[Settlement]
  H & R & S & PC & RF --> P[LedgerPostingService] --> DB[(MySQL)]
  KU --> A --> DB
  H & R & S & PC & RF --> OB[Outbox] -.relay.-> Kafka
```

**A hold, step by step:**
1. Validate the body (422 / 413).
2. Insert `hold_decision` (PENDING). If the row exists, return the stored answer.
3. Read the closure rows FOR SHARE; refuse if closed.
4. Lock the student's accounts; refuse if short.
5. Post AVAILABLE → HELD, save PLACED / OPEN, and queue HoldPlaced in the outbox.

Everything commits together.

## 6. Deep dives

**Lock order** (decision 0004), the rule that prevents deadlocks:
1. Business rows: holds by order_id, then closure and claim rows.
2. Student accounts, all at once, ascending id.
3. The outbox counter, which `publish` locks.

Union and restaurant accounts have no running balance, so they're never locked (decision 0005, `tutorial-nosql.pdf` pp. 3–9: hot rows).

**Settlement** (decision 0007): one local transaction, not a saga, because every row is ours (`lecture12a-the-on-sale-1.pdf` pp. 6–11).
- The inbox catches a repeated envelope; `deal_settlement` catches a new envelope for the same deal.
- A crash rolls everything back, and redelivery settles once.
- Measured: 202 orders in 0.79 s during k6, so holds for those students still answer within 2 s ([evidence](evidence/led-22-holds-k6.md)).

**Chaining** (LED-20): every balance on an event is read under the student lock. The outbox numbers events per key in commit order: per-key FIFO, not total order (`lecture10c_2.pdf` pp. 3–9).

### Cash-outs and payouts (LED-15, LED-16, LED-17, item 8)

Money leaving the system. Payments runs the desk and pays restaurants; the Ledger only records it, and only after checking the student has the money.

**A cash-out happens in two steps**, like an order: the money is set aside first, then the outcome decides where it goes.

The example is the contracts' own: a student topped up €20 at the desk and has €6 set aside for an order, so €14 is available. They ask for €10 cash.

| Step | Payments sends | The Ledger checks | Money | Available / set aside after | The Ledger announces | `withdrawal.state` |
|---|---|---|---|---|---|---|
| 1. Student asks for €10 cash | `WithdrawalRequested` €10 | Seen this withdrawalId? Enough available? | €10 set aside: AVAILABLE → HELD | €4 / €16 | `WithdrawalDebited` | RESERVED |
| 1. …or asks for all €20 they topped up | `WithdrawalRequested` €20 | Only €14 available | None | €14 / €6 | `WithdrawalRefused` (INSUFFICIENT_FUNDS) | REFUSED |
| 2a. Desk hands the €10 over | `WithdrawalCompleted` | Still RESERVED? | €10 leaves: HELD → Union CASH | €4 / €6 | `WithdrawalPaidOut` | PAID_OUT |
| 2b. Cash-out cancelled | `WithdrawalFailed` | Still RESERVED? | €10 back: HELD → AVAILABLE | €14 / €6 | `WithdrawalReturned` | RETURNED |

**A payout is one step:** `PayoutConfirmed` (the contracts' example: €129.60 for one restaurant's week) takes the amount from the restaurant's PAYABLE to Union CASH and announces `PayoutDebited`. Payments decides how much, so the payable may go below zero.

**Why it can't go wrong:**
- **Set aside first.** The cash can't also be spent on an order while staff count it out. A cash-out and an order racing for the same money both lock the student's row, so one wins and the other is refused.
- **Ends once.** Step 2 only acts on a RESERVED row, so whichever of Completed or Failed arrives first wins; the other changes nothing. A cash-out is never both paid and returned.
- **The reserved amount wins.** Step 2 moves what was set aside, whatever amount the later event carries; a different one is logged (`warn`).
- **Repeats do nothing.** The `withdrawal` row (by withdrawalId) and the movement key (by payoutId) stop a second effect; the inbox stops a replayed message.
- **Bad input is dead-lettered.** A €0 cash-out or payout goes to `ledger.dlt` (LED-19).
- **Unknown or refused cash-out.** A Completed or Failed for it logs a warning and moves nothing.

Lock order (decision 0004): the `withdrawal` row, then the student, then publish. Both flows are branches of the one `payments.v1` listener (decision 0006).

```mermaid
sequenceDiagram
  autonumber
  participant P as Payments desk
  participant K as Kafka payments.v1
  participant C as PaymentsConsumer
  participant W as Withdrawals
  participant DB as Ledger database
  participant O as Outbox to ledger.v1

  Note over P,O: Step 1 - reserve (LED-15)
  P->>K: WithdrawalRequested 10 euro
  K->>C: deliver, inbox skips a repeat
  C->>W: request
  W->>DB: claim the withdrawal row, a repeat stops here
  W->>DB: lock the student and read the balance
  alt enough available
    rect rgba(255, 193, 7, 0.25)
      W->>DB: AVAILABLE -10 and HELD +10, state RESERVED
      W->>O: WithdrawalDebited
    end
  else not enough
    rect rgba(128, 128, 128, 0.25)
      W->>DB: state REFUSED, no money moves
      W->>O: WithdrawalRefused
    end
  end

  Note over P,O: Step 2 - end it once (LED-16)
  P->>K: WithdrawalCompleted or WithdrawalFailed
  K->>C: deliver, inbox skips a repeat
  C->>W: completed or failed
  W->>DB: lock the row and check it is still RESERVED
  alt Completed
    rect rgba(76, 175, 80, 0.25)
      W->>DB: HELD -10 and Union CASH +10, state PAID_OUT
      W->>O: WithdrawalPaidOut
    end
  else Failed
    rect rgba(33, 150, 243, 0.25)
      W->>DB: HELD -10 and AVAILABLE +10, state RETURNED
      W->>O: WithdrawalReturned
    end
  end
```

Same colours as the states below: yellow RESERVED, grey REFUSED, green PAID_OUT, blue RETURNED.

```mermaid
stateDiagram-v2
  [*] --> RESERVED: Requested, enough available<br/>AVAILABLE → HELD
  [*] --> REFUSED: Requested, not enough<br/>nothing moves
  RESERVED --> PAID_OUT: Completed<br/>HELD → Union CASH
  RESERVED --> RETURNED: Failed<br/>HELD → AVAILABLE
  REFUSED --> [*]
  PAID_OUT --> [*]
  RETURNED --> [*]

  classDef reserved fill:#fff3cd,stroke:#b8860b,color:#5c4400
  classDef refused fill:#e0e0e0,stroke:#616161,color:#212121
  classDef paid fill:#c8e6c9,stroke:#2e7d32,color:#1b5e20
  classDef returned fill:#bbdefb,stroke:#1565c0,color:#0d3c78
  class RESERVED reserved
  class REFUSED refused
  class PAID_OUT paid
  class RETURNED returned
```

Yellow: money set aside, waiting. Grey: refused, nothing moved. Green: cash left the Ledger. Blue: money back in the student's Account.

Proof: `WithdrawalsTest` (8 container tests, including the race), `ContractTest` (every new event, zero amounts to `ledger.dlt`).

## 7. Failures

| Failure | Ledger's response |
|---|---|
| Orders times out after commit | Retry returns the stored answer |
| Same order on both instances | Primary key: the second waits, then reads the first |
| Database down | API calls: 503 within about 1 s (stopped) or 2 to 10 s (not answering). Events wait for it, up to 15 min, then the dead-letter topic |
| Kafka down | Events wait in the outbox, then go out in order |
| Instance killed mid-settlement | Rollback; redelivery settles once |
| Duplicate event | Inbox (event id) and business key (topUpId, orderId, ...) |
| Refund before its charge | Parked, woken by the charge or release |
| Zero amount, unreadable message | Dead-letter topic; consumer moves on |
| Deadlock | Hold retries within 1 s; consumer redelivers |

**First failure worth testing:** kill an instance mid-settlement while holds arrive for the same students.

## Kept from the starter

`platform/` untouched. One package, `service/ledger`. JdbcTemplate, Flyway, `ContainersTest`. Time only from the injected `Clock`.
