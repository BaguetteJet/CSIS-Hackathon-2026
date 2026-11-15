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

### 1. Top-up

Student hands €10 to the desk. Payments sends `TopUpRecorded`.

- +1 `processed_event` row
- Create 2 student rows if missing (balance zero), AVAILABLE `balance_cents` +1000
- +1 movement row (`TOP_UP`, key `topUpId`)
- +2 postings rows (STUDENT AVAILABLE +1000, UNION CASH −1000)
- +1 event out row `TopUpCredited`

| Table | What happens |
| --- | --- |
| `processed_event` | Add 1 row `(consumer, event_id)`. Claims the event so a redelivery finds the row and skips. |
| `ledger_account` | Add 2 student rows if missing (set balance 0). Update STUDENT AVAILABLE `balance_cents` +1000. The UNION CASH row already exists; its `balance_cents` is not updated. |
| `ledger_movement` | Add 1 row `reference_type = TOP_UP`, `reference_id = topUpId`, new UUID, `posted_at`. The unique `(reference_type, reference_id)` is what stops the same top-up being applied by a new event. |
| `ledger_posting` | Add 2 rows, same `movement_id`: STUDENT AVAILABLE +1000, UNION CASH −1000. The student side gets `entry_kind = TOP_UP` and the statement description; `deal_id`/`order_id` stay null. |
| `outbox_event` | Add 1 row: topic `ledger.v1`, key `studentId`, type `TopUpCredited`, message JSON, `published_at = null`. Written in the same transaction as the money. |
| `key_sequence` | The `(topic, event_key)` row is inserted or incremented, giving the event its per-student sequence number. |                                                                                                          |

**Invalid amount:** If the amount is 0 or negative, the transaction writes nothing to any of these tables and the message goes to `ledger.dlt`.

**Same `topUpId` again:** the unique `(reference_type, reference_id)` on `ledger_movement` rejects it, so the money does not move twice.

### 2. Hold (the one direct call)

Student orders a €7 lunch. Orders calls `POST /internal/ledger/holds`. Synchronous HTTP call from Orders, not a Kafka event/

Checks, in order: valid request? seen this `orderId` before? order or deal already closed? enough money?

If all pass:

- Create 2 student rows if missing (balance zero)
- +1 `hold_decision` row (PLACED, state OPEN)
- +1 movement row (`HOLD`, key `orderId`)
- +2 posting rows (STUDENT AVAILABLE −700, STUDENT HELD +700)
- +1 event out row `HoldPlaced`

| Table | What happens |
| --- | --- |
| `hold_decision` | Add 1 row `order_id` PK, `student_id`, `deal_id`, `amount_cents = 700`, `result = PLACED`, `available_cents = null`, `decided_at`, `state = OPEN`. Inserted first: if this row already exists the call returns the stored answer instead. |
| `ledger_account` | Add 2 student rows if missing (set balance 0). Lock both. Update STUDENT AVAILABLE `balance_cents` −700, STUDENT HELD `balance_cents` +700. |
| `ledger_movement` | Add 1 row `reference_type = HOLD`, `reference_id = orderId`, new UUID, `posted_at`. The unique `(reference_type, reference_id)` stops a new event for the same order moving money again. |
| `ledger_posting` | Add 2 rows, same `movement_id`: STUDENT AVAILABLE −700, STUDENT HELD +700. The student AVAILABLE side gets `entry_kind = HOLD` and the statement wording. |
| `outbox_event` | Add 1 row: topic `ledger.v1`, key `studentId`, type `HoldPlaced`, message JSON, `published_at = null`. Written in the same transaction as the money. |
| `key_sequence` | The `(topic, event_key)` row is inserted or incremented, giving the event its per-student sequence number. |

**Not enough money:** if the student has only €5, nothing moves. The `hold_decision` row says `INSUFFICIENT_FUNDS` with `available_cents = 500` and `state = null`; the outbox gets `HoldRejected`. No movement or postings.

**Order or deal already closed:** same, but the row says `ORDER_CLOSED` or `DEAL_CLOSED`; outbox `HoldRejected`.

**Same `orderId` again:** return the stored `hold_decision` row. Write nothing new.

**Invalid request:** 422 or 413; nothing is written.

A student balance never goes below zero.

### 3. Release

The deal fails, the student withdraws, or Orders abandons the order. Orders or Deals sends the event.

- +1 `processed_event` row
- +1 `order_closure` or `deal_closure` row
- +1 `hold_decision` update (OPEN → RELEASED)
- +1 movement row (`RELEASE`, key `orderId`)
- +2 posting rows (STUDENT HELD −700, STUDENT AVAILABLE +700)
- +1 event out row `HoldReleased`

| Table | What happens |
| --- | --- |
| `processed_event` | Add 1 row `(consumer, event_id)`. Claims the event so a redelivery finds the row and skips. |
| `order_closure` / `deal_closure` | Add 1 row keyed `order_id` or `deal_id`. `ON DUPLICATE KEY` does nothing, so the first closure wins. |
| `hold_decision` | `UPDATE ... SET state = 'RELEASED' WHERE order_id = ? AND state = 'OPEN'`. 0 rows updated means the hold was already released or charged, so stop. |
| `ledger_account` | Update STUDENT HELD `balance_cents` −700, STUDENT AVAILABLE `balance_cents` +700. |
| `ledger_movement` | Add 1 row `reference_type = RELEASE`, `reference_id = orderId`. |
| `ledger_posting` | Add 2 rows: STUDENT HELD −700, STUDENT AVAILABLE +700. The student AVAILABLE side gets `entry_kind = RELEASE`. |
| `outbox_event` | Add 1 row: topic `ledger.v1`, key `studentId`, type `HoldReleased`, `published_at = null`. |
| `key_sequence` | Insert or increment the `(topic, event_key)` row for the student. |

A `DealFailed` or `DealCancelled` writes one `deal_closure` row, then releases each of that deal's holds that are still OPEN, one `RELEASE` movement per order.

### 4. Settlement

The deal confirms at €5, with 10% commission (€0.50). The student had €7 set aside.

- +1 `processed_event` row
- +1 `deal_settlement` row (claimed first)
- +1 `deal_closure` row (CONFIRMED)
- Per charged order: hold state CHARGED, movement `ORDER_CHARGE`, 2–4 posting rows, `order_charge` row, `OrderCharged` event
- Per unlisted open hold: a `RELEASE` movement and `HoldReleased`
- Per problem: a `SettlementIssue` event
- One `DealSettled` event

The €7 hold becomes a €5 charge:

| Account | Amount |
|---|---|
| Student held | −€7.00 |
| Restaurant payable | +€4.50 |
| Union commission | +€0.50 |
| Student available | +€2.00 |
| **Sum** | **0** |

| Table | What happens |
| --- | --- |
| `processed_event` | Add 1 row `(consumer, event_id)`. A repeated `DealConfirmed` is skipped here. |
| `deal_settlement` | Add 1 row `deal_id` PK, `restaurant_id`, `settled_at`; totals filled at the end. Inserted first: a second envelope for the same deal stops here. |
| `deal_closure` | Add 1 row `deal_id` PK, `reason = CONFIRMED`. |
| `hold_decision` | `UPDATE ... SET state = 'CHARGED' WHERE order_id = ? AND state = 'OPEN'`. 0 rows updated means the order is not chargeable; report `SettlementIssue` instead. |
| `ledger_account` | Update STUDENT HELD `balance_cents` −700, STUDENT AVAILABLE `balance_cents` +200. Restaurant and Union balances are not kept; they are summed from postings. |
| `ledger_movement` | Add 1 row per charged order: `reference_type = ORDER_CHARGE`, `reference_id = orderId`. |
| `ledger_posting` | Add 2–4 rows per charge, same `movement_id`: STUDENT HELD −700, RESTAURANT PAYABLE +450, UNION COMMISSION +50, STUDENT AVAILABLE +200. Lines that would be 0 are left out. Only the student AVAILABLE side gets `entry_kind = RETURNED` when change comes back. |
| `order_charge` | Add 1 row `order_id` PK, `deal_id`, `student_id`, `restaurant_id`, `price_cents = 500`, `commission_cents = 50`, `returned_cents = 200`, `refunded_cents = 0`, `commission_reversed_cents = 0`, `charged_at`. |
| `outbox_event` | Add `OrderCharged` per charged order (key `studentId`), `SettlementIssue` per problem (key `dealId`), one `DealSettled` (key `restaurantId`), all with `published_at = null`. |
| `key_sequence` | Insert or increment the `(topic, event_key)` row for each event's key. |

The €2 is not a refund. It is the difference between what was set aside and what the order actually cost.

**An order that cannot be charged** (no hold, already released, another student or deal, below the price) isn't charged; it gets a `SettlementIssue`, and if its money is set aside for this deal, that money comes back (released NOT_ON_LIST).

### 5. Read a balance

`GET /api/ledger/me` returns the student's **available** and **set aside** amounts. It reads the student's running balance under a lock, so it never shows a half-finished move.

| Table | What happens |
| --- | --- |
| `ledger_account` | Read the STUDENT AVAILABLE and HELD `balance_cents` under the student lock. No rows change. Never read UNION or RESTAURANT `balance_cents`; those are stale, so use `SUM(ledger_posting.amount_cents)` instead. |
| `ledger_posting` | Only for the planned statement (`GET /api/ledger/me/entries`), which reads the student's AVAILABLE-side `entry_kind`, `description`, `deal_id` and `order_id`. |

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
