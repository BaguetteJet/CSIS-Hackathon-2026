# Neighbours: who needs what from the Ledger

Drafted Sat 14 Nov from each team's document (`per_team_spec.pdf`, the "You read" lists) and Orders' design decisions. **Confirmed** means that team's lead said this is complete. Event shapes: contracts 1.3.0, reference Appendix F.

**By:** Sat 16:30, Sat 21:00 and Sun 14:00 are the spec's (Ledger §8); Sun 12:00 is our own target for the rest (PHASES, phase 3).

## What they need from us

| Team | Needs | How | By | If we fail | Built | Confirmed |
|---|---|---|---|---|---|---|
| Orders | Hold answer: PLACED, INSUFFICIENT_FUNDS, ORDER_CLOSED, DEAL_CLOSED | `POST /internal/ledger/holds`, the one direct call | Sat 16:30 | Orders stay PENDING and are abandoned after 2 min | Yes (LED-03, 04) | Their doc, not yet in person |
| Orders | HoldPlaced, HoldRejected | ledger.v1 | Sat 16:30 | Pending orders aren't settled from the event; only their retry every 20 s does it | Yes | Their doc |
| Orders | OrderCharged | ledger.v1 | Sat 21:00 | Nothing reaches PAID | Yes (LED-05) | Their doc |
| Orders | RefundPosted | ledger.v1 | Sun 12:00 | Nothing reaches REFUNDED | No (item 6) | Their doc |
| Payments | TopUpCredited | ledger.v1 | Sat 16:30 | Desk top-ups and the reset's €50 show "waiting for the Ledger" | Yes (LED-02) | No |
| Payments | WithdrawalDebited, WithdrawalRefused, WithdrawalPaidOut, WithdrawalReturned | ledger.v1 | Sun 12:00 | Cash-outs stay REQUESTED | No (item 8) | No |
| Payments | OrderCharged, RefundPosted, PayoutDebited | ledger.v1 | Sun 12:00 | Restaurant payables and the payout file are wrong | OrderCharged only | No |
| Payments | A student's available balance at the desk | `GET /api/ledger/students/{studentId}` | Sun 12:00 | Desk shows the balance as unknown, still lets staff start a cash-out | No (item 7) | No |
| Support | OrderCharged, HoldReleased, RefundPosted, RefundRefused, SettlementIssue | ledger.v1 | Sun 12:00 | Support can't see charges or answer refunds | Charges, releases, issues; refunds no | No |
| Student experience | Balance and statement on the Account page | `GET /api/ledger/me`, `GET /api/ledger/me/entries` | Sun 12:00 | The Account page has no balance or history | `/me` yes; entries no (item 7) | No |
| Student experience | Charges, refunds and desk money | ledger.v1 | Sun 12:00 | Pages and emails miss money events | Charges and top-ups | No |
| Platform and reliability | Every ledger.v1 event, `ledger.dlt`, metrics; one alert per SettlementIssue (PLT-12) | Kafka, `/actuator/prometheus` | Now | Their alerts and stuck-hold timer are blind | Yes | No |
| Deals, Restaurant operations | Nothing: they never wait for settlement | | | | | Their docs |

## What we need from them

| Team | We read | For |
|---|---|---|
| Orders | The hold call body; OrderWithdrawn, OrderAbandoned (orders.v1) | Holds; release and close the order |
| Deals | RestaurantRegistered, DealConfirmed, DealFailed, DealCancelled (deals.v1) | Open payables; settle; release and close the deal |
| Payments | TopUpRecorded, WithdrawalRequested, WithdrawalCompleted, WithdrawalFailed, PayoutConfirmed (payments.v1) | Credit; reserve; pay out; return; debit the payable |
| Support | RefundApproved (support.v1) | Post, park or refuse a refund |
| Platform | UserRegistered (users.v1), the clock | Open student accounts; the time |

## Asking each team

One message per team lead, in the line channel. Tick **Confirmed** when they answer.

> Hi, Ledger here. From your team document, you need these from us: <rows above>. Anything missing, or anything you'd use differently? Of these, <built ones> are on `main` and promoted from <tag>.
