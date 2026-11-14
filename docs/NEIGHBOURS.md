# Neighbours: what we own, what we need, what we give

Drafted Sat 14 Nov from each team's document (`per_team_spec.pdf`, the "You read" lists) and Orders' design decisions. Fields and keys checked against contracts 1.3.0 (`contracts/schemas/`, `contracts/openapi/` and `EventCatalogue` in the library's sources jar). **Confirmed** means that team's lead said this is complete.

**By:** Sat 16:30, Sat 21:00 and Sun 14:00 are the spec's (Ledger §8); Sun 12:00 is our own target for the rest (PHASES, phase 3).

## 1 What we own, what we don't

Every other service touches money only through us. We own the books; we never own the business decision that moves them.

| The Ledger owns | Everyone else |
|---|---|
| Every account and balance: students (available, set aside), restaurants (payable), the Union (cash, commission) | Reads balances from our events or our API; keeps none of its own |
| The hold answer for each order | Orders acts on it |
| What each order paid: price, commission, returned | Takes it from OrderCharged |
| Whether an approved refund is paid: never more than the charge | Support approves; we post or refuse |
| Reserving, paying out and returning a cash-out in the books | Payments runs the desk and the cash-out's status |
| Taking a payout from a restaurant's payable, once | Payments makes the payout file and confirms the payout |
| The statement and the desk balance | Pages show them |
| What goes on ledger.v1 (its shape is the contracts') | Consumes it |

| We don't own | Owner | We only |
|---|---|---|
| Orders and their status (PENDING, PAID, REFUNDED...) | Orders | Answer the hold call; read OrderWithdrawn, OrderAbandoned |
| Deals: tiers, price, commission rate, cap, cut-off, tipping | Deals | Read DealConfirmed, DealFailed, DealCancelled, RestaurantRegistered; take price and rate as given |
| The desk, its cash, cash-out status, the payout file, the daily reconciliation (ReconciliationMismatch, including its LEDGER check) | Payments | Read TopUpRecorded, Withdrawal\*, PayoutConfirmed |
| Refund approval, complaints, blocked accounts, deletions | Support | Read RefundApproved; ignore the rest of support.v1 |
| Users, roles, the sign-in key, the gateway, the clock, Kafka and MySQL in integration | Platform | Read UserRegistered; take the time from the clock |
| Alerts: stuck holds (PLT-11), one per SettlementIssue (PLT-12); the load test (PLT-14) | Platform | Publish the events and metrics they read |
| The Account page, emails | Student experience | Serve `/me` and `/me/entries` |
| Event and API shapes (contracts 1.3.0), the answers log | Organisers | Code against them; a change reaches us through the answers log (item 1, step 9) |
| `src/.../platform/`, `example/` | Organisers' starter | Leave it as it is |

## 2 What we need to update the Ledger

Every change of money starts from one of these. A field not listed is in the contract but unused. Each event arrives in an envelope: the inbox skips a repeated `eventId`, and that `eventId` becomes the `causationId` of every event we publish because of it.

| Change | From | Event or call | Fields we use | Business key | Status |
|---|---|---|---|---|---|
| Open a student's accounts | Platform | UserRegistered (users.v1) | `userId`, `role` = STUDENT | `userId` | Built (LED-01) |
| Open a restaurant's payable | Deals | RestaurantRegistered (deals.v1) | `restaurantId` | `restaurantId` | Built (LED-01) |
| Credit a top-up | Payments | TopUpRecorded (payments.v1) | `topUpId`, `studentId`, `amountCents` ≥ 1 | `topUpId` | Built (LED-02) |
| Set money aside | Orders | `POST /internal/ledger/holds` | `orderId`, `studentId`, `dealId`, `amountCents` ≥ 1; `cutoffAt` is checked but never used: we never refuse for time | `orderId` | Built (LED-03, 04) |
| Release an order's hold | Orders | OrderWithdrawn, OrderAbandoned (orders.v1) | `orderId`, `dealId`; the student and amount come from our hold | `orderId` | Built (LED-08) |
| Release a deal's holds | Deals | DealFailed, DealCancelled (deals.v1) | `dealId` | `dealId`; `orderId` per release | Built (LED-09) |
| Settle a deal | Deals | DealConfirmed (deals.v1) | `dealId`, `restaurantId`, `priceCents`, `commissionBasisPoints` (0 to 10000), `orders[]` of `orderId` and `studentId`; the amount set aside comes from our hold | `dealId`; `orderId` per charge | Built (LED-05, 06, 10) |
| Refund after a charge | Support | RefundApproved (support.v1) | `refundId`, `orderId`, `dealId`, `studentId`, `amountCents` ≥ 1, `reason` | `refundId` | Built (LED-13); waking early ones: item 6 |
| Reserve a cash-out | Payments | WithdrawalRequested (payments.v1) | `withdrawalId`, `studentId`, `amountCents` ≥ 1 | `withdrawalId` | Item 8 |
| Pay out or return a cash-out | Payments | WithdrawalCompleted, WithdrawalFailed (payments.v1) | `withdrawalId`; the amount is the one we reserved | `withdrawalId` | Item 8 |
| Pay a restaurant | Payments | PayoutConfirmed (payments.v1) | `payoutId`, `restaurantId`, `amountCents` ≥ 1 | `payoutId` | Item 8 |
| Every timestamp | Platform | The clock (`CLOCK_URL`) | Now | | Built |

Commission comes from DealConfirmed, never from RestaurantRegistered: the deal's rate is the one charged. How each change is written: [schema §4](schema.md#4-what-each-operation-writes).

## 3 What they need from us

| Team | Needs | How | By | If we fail | Built | Confirmed |
|---|---|---|---|---|---|---|
| Orders | Hold answer: PLACED, INSUFFICIENT_FUNDS, ORDER_CLOSED, DEAL_CLOSED | `POST /internal/ledger/holds`, the one direct call | Sat 16:30 | Orders stay PENDING and are abandoned after 2 min | Yes (LED-03, 04) | Their doc, not yet in person |
| Orders | HoldPlaced, HoldRejected | ledger.v1 | Sat 16:30 | Pending orders aren't settled from the event; only their retry every 20 s does it | Yes | Their doc |
| Orders | OrderCharged | ledger.v1 | Sat 21:00 | Nothing reaches PAID | Yes (LED-05) | Their doc |
| Orders | RefundPosted | ledger.v1 | Sun 12:00 | Nothing reaches REFUNDED | Yes (LED-13) | Their doc |
| Payments | TopUpCredited | ledger.v1 | Sat 16:30 | Desk top-ups and the reset's €50 show "waiting for the Ledger" | Yes (LED-02) | No |
| Payments | WithdrawalDebited, WithdrawalRefused, WithdrawalPaidOut, WithdrawalReturned | ledger.v1 | Sun 12:00 | Cash-outs stay REQUESTED | No (item 8) | No |
| Payments | OrderCharged, RefundPosted, PayoutDebited | ledger.v1 | Sun 12:00 | Restaurant payables and the payout file are wrong | OrderCharged, RefundPosted | No |
| Payments | A student's available balance at the desk | `GET /api/ledger/students/{studentId}` (UNION_FINANCE) | Sun 12:00 | Desk shows the balance as unknown, still lets staff start a cash-out | Yes | No |
| Support | OrderCharged, HoldReleased, RefundPosted, RefundRefused, SettlementIssue | ledger.v1 | Sun 12:00 | Support can't see charges or answer refunds | Yes; NEVER_CHARGED not yet (item 6) | No |
| Student experience | Balance and statement on the Account page | `GET /api/ledger/me`, `GET /api/ledger/me/entries` (STUDENT) | Sun 12:00 | The Account page has no balance or history | `/me` and entries yes | No |
| Student experience | Charges, refunds and desk money | ledger.v1 | Sun 12:00 | Pages and emails miss money events | Charges, refunds and top-ups | No |
| Platform and reliability | Every ledger.v1 event, `ledger.dlt`, metrics; one alert per SettlementIssue (PLT-12) | Kafka, `/actuator/prometheus` | Now | Their alerts and stuck-hold timer are blind | Yes | No |
| Deals, Restaurant operations | Nothing: they never wait for settlement | | | | | Their docs |

## 4 Event keys

Events with the same key arrive in the order we committed them (per-key FIFO, ARCHITECTURE §6). From `EventCatalogue`:

| ledger.v1 events | Key |
|---|---|
| HoldPlaced, HoldRejected, HoldReleased, OrderCharged, RefundPosted, RefundRefused, TopUpCredited, WithdrawalDebited, WithdrawalRefused, WithdrawalPaidOut, WithdrawalReturned | `studentId` |
| DealSettled, PayoutDebited | `restaurantId` |
| SettlementIssue | `dealId` |

So a student's events chain by balance (LED-20), but a deal's DealSettled can arrive before or after its students' OrderCharged.

## 5 Asking each team

One message per team lead, in the line channel. Tick **Confirmed** when they answer.

> Hi, Ledger here. From your team document, you need these from us: <rows above>. Anything missing, or anything you'd use differently? Of these, <built ones> are on `main` and promoted from <tag>.
