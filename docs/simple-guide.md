# Ledger (SIMPLE)

## Intro

The Ledger is the "bank" for Campus Group-Buy. It decides **whether a student's money is available**, and records where every cent went.

Students' union plays the "bank" role. It takes cash in the desk from students, holds everyone's money, owes restaurants their payable, and earns commission. In the schema it has two accounts: 

1. **UNION CASH** - holds student cash
2. **UNION COMMISSION** - earned from charging students

Every money **movement** is a set of **postings** (signed amounts on accounts) that sum to **zero**. Money is never created or destroyed.

So how does money appear in the system? Only through recorded top-ups. 

Start at zero. Student tops up €10 at the desk.

| Account | Amount |
|---|---|
| Student available | +€10 |
| Union cash | −€10 |
| **Sum** | **0** |

The Union cash account goes negative because it mirrors holding €10 for this student.

In the dev kit, students are funded with ./sim money-in, which just sends a batch of TopUpRecorded events.

## Accounts

Stored in `ledger_account`. Each account is a row of (owner_type, owner_id, purpose), unique on all three. The exact combinations are:

| owner_type | owner_id | purpose | Plain Meaning |
|---|---|---|---|
| STUDENT | `student_id` (from other services) | AVAILABLE | Money the student can spend |
| STUDENT | `student_id` (from other services) | HELD | Money reserved for open orders |
| RESTAURANT | `restaurant_id` (from Deals service) | PAYABLE | What the Union owes the restaurant |
| UNION | `00000000-0000-0000-0000-000000000000` | CASH | Money the Union holds for everyone. top-ups push it down (negative), cash-outs and payouts push it up |
| UNION | `00000000-0000-0000-0000-000000000000` | COMMISSION | What the Union has earned from charges |

A student has two accounts. A restaurant has one. The Union has two. Only student balances are kept as a running number. The rest are added up from postings.

Union accounts exist from first start, other accounts open on first use.

- Payments/cash-out terms in the docs map onto these: a cash-out reserve is AVAILABLE → HELD, a payout is RESTAURANT/PAYABLE → UNION/CASH, a top-up is UNION/CASH → STUDENT/AVAILABLE.

## Flows

Events come in:

| Table | What happens |
| --- | --- |
| `processed_event` | Add 1 row `(consumer, event_id)`. Claims event so a redelivery finds the row and skips. 

### 1. Top-up

Student hands €10 to the desk. Payments send `TopUpRecorded`.

- Create 2 student rows if missing (balance zero), AVAILABLE `balance_cents` +1000
- +1 movement row (`TOP_UP`, key `topUpId`)
- +2 postings rows (STUDENT AVAILABLE +1000, UNION CASH −1000)
- +1 event out row `TopUpCredited`

| Table | What happens |
| --- | --- |
| `ledger_account` | Add 2 student rows if missing (set balance 0). Update STUDENT AVAILABLE `balance_cents` +1000. Update UNION CASH row already exists, balance not updated. |
| `ledger_movement` | Add 1 row `reference_type = TOP_UP`, `reference_id = topUpId`, new UUID, `posted_at`. The unique `(reference_type, reference_id)` is what stops the same top-up being applied by a new event. |
| `ledger_posting` | Add 2 rows, same `movement_id`: STUDENT AVAILABLE +1000, UNION CASH −1000. The student side gets `entry_kind = TOP_UP` and the statement description; `deal_id`/`order_id` stay null. |
| `outbox_event` | Add 1 row: topic `ledger.v1`, key `studentId`, type `TopUpCredited`, message JSON, `published_at = null`. Written in the same transaction as the money. |
| `key_sequence` | The `(topic, event_key)` row is inserted or incremented, giving the event its per-student sequence number. |                                                                                                          |

**Invalid amount:** If the amount is 0 or negative, the transaction writes nothing to any of these tables and the message goes to `ledger.dlt`.

### 2. Hold (the one direct call)

Student orders a €7 lunch. Orders calls `POST /internal/ledger/holds`.

Checks, in order: valid request? seen this `orderId` before? order or deal already closed? enough money?

If all pass:

- Student available **−€7**
- Student set aside **+€7**
- `hold_decision` row: PLACED, state OPEN
- Event out: `HoldPlaced`

If the student has only €5:

- Nothing moves
- `hold_decision` row: INSUFFICIENT_FUNDS, `available_cents` = 500
- Event out: `HoldRejected`

A student balance never goes below zero.

### 3. Release

The deal fails, or the student withdraws.

- Student set aside **−€7**
- Student available **+€7**
- Hold state: OPEN → RELEASED
- One movement (`RELEASE`, key `orderId`)
- Event out: `HoldReleased`

### 4. Settlement

The deal confirms at €5, with 10% commission (€0.50). The student had €7 set aside.

- Student set aside **−€7**
- Restaurant payable **+€4.50**
- Union commission **+€0.50**
- Student available **+€2.00** (the change)
- **Sum: 0**
- `order_charge` row: price €5, commission €0.50, returned €2
- `deal_settlement` row: the deal is settled
- Events out: `OrderCharged` per order, one `DealSettled`

The €2 is not a refund. It is the difference between what was set aside and what the order actually cost.

### 5. Read a balance

`GET /api/ledger/me` returns the student's **available** and **set aside** amounts. It reads the student's running balance under a lock, so it never shows a half-finished move.

## The tables, in plain words

| Group | Tables | Remembers |
|---|---|---|
| Money | `ledger_account`, `ledger_movement`, `ledger_posting` | Every balance and every move |
| Decisions | `hold_decision`, `order_closure`, `deal_closure`, `order_charge`, `deal_settlement` | What we already decided: hold answers, closed orders/deals, charges, settlements |
| Plumbing | `processed_event`, `outbox_event`, `parked_event`, etc. | Events in once, events out in order, early events, timers |

Only two real database foreign keys exist: a posting points at its movement and its account. Everything else links by shared IDs (`order_id`, `deal_id`, `student_id`).

## Why repeats do not double anything

| Repeat | Stopped by |
|---|---|
| Same top-up arrives twice | `ledger_movement` unique (`TOP_UP`, `topUpId`) |
| Same order asks for a hold twice | `hold_decision` primary key `order_id`; the stored answer is returned |
| Hold is released and charged | `UPDATE ... WHERE state = 'OPEN'`; only one side wins |
| Same deal confirms twice | `deal_settlement` primary key `deal_id` is claimed first |
| Same order charged twice | `order_charge` primary key `order_id` |
| Same event delivered twice | `processed_event` primary key (`consumer`, `event_id`) |

## Two instances, one truth

`ledger-1` and `ledger-2` share one MySQL database. Either can answer any call or event. The database decides races, not the instance.

Locks are taken in a fixed order: business rows, then student accounts by ascending id, then the outbox counter. That prevents deadlocks.

## Not built yet

Refunds, cash-outs, payouts, the statement and the desk balance are planned (LED-11 to LED-17). The tables and movements are designed for them but the code is not there yet.
