# Edge cases

Every case the Ledger must handle, and what it does. The spec for [item 9](../work-items/item9_robustness.md); each row becomes a test. Design: [ARCHITECTURE.md](ARCHITECTURE.md). Tables: [schema.md](schema.md).

**Every decision is recorded, declines included.** Only the money depends on the outcome.

## How to read the table

| Column | Means |
|---|---|
| # | H order, D deal, T top-up, W cash-out, R refund, P payout, O outage, K repeat (idempotency), X worst case |
| In code? | ✅ the code handles it today; ⚠️ partly (gap in Why it matters or Status); ❌ not written yet; ➖ Not ours: another service's job |
| What happens | The situation, in plain words |
| Comes in | The call or event, and who sends it |
| Ledger checks | In order; the first that fails decides |
| Result | ✅ approved, ❌ declined (reason), ➖ no change, ⏸ parked |
| Money | What moves; each movement sums to zero. "None" if nothing moves |
| Recorded and announced | What is saved, and the event other services get on `ledger.v1` |
| Why it matters | What would go wrong without this; **(marked)** = a marked property |
| Status | Done (test that proves it), To do (item and step), or ❓ open question |

Amounts are examples: a student with €10, an order of €7, a 10% commission.

## Cases

| In code? | # | Area | What happens | Comes in | Ledger checks | Result | Money | Recorded and announced | Why it matters | Status |
|---|---|---|---|---|---|---|---|---|---|---|
| ✅ Yes | H1 | Order | Student orders and has enough money | Hold call €7 (Orders) | 1 valid? 2 seen this orderId? 3 order or deal closed? 4 enough money? | ✅ PLACED | €7 set aside: AVAILABLE → HELD | `hold_decision` PLACED; `HoldPlaced` | Stops the same money being spent twice | Done (`HoldsTest`) |
| ✅ Yes | H2 | Order | Student orders without enough money | Hold call €7, has €5 | Check 4 fails | ❌ INSUFFICIENT_FUNDS, tells Orders €5 is available | None | `hold_decision` INSUFFICIENT_FUNDS; `HoldRejected` | No balance ever goes below zero (marked) | Done (`HoldsTest`) |
| ✅ Yes | H3 | Order | Orders sends the same order again (a retry) | Same orderId, same amount | Check 2: seen before | ➖ Same answer as the first time | None | Nothing new | A retry can't set money aside twice | Done (`HoldsTest`) |
| ⚠️ Partly | H4 | Order | Same orderId comes back with a different amount | Same orderId, €9 | Check 2: seen before | ➖ First answer stands | None | Nothing new; ❓ warning log and counter | A retry can't change a decision | To do (item 9 step 3) |
| ✅ Yes | H5 | Order | Order arrives after the student withdrew it | Hold call €7 | Check 3: order closed | ❌ ORDER_CLOSED | None | `hold_decision` ORDER_CLOSED; `HoldRejected` | A late order can't leave money stuck (marked) | Done (`HoldsTest`) |
| ✅ Yes | H6 | Order | Order arrives after the deal closed | Hold call €7 | Check 3: deal closed | ❌ DEAL_CLOSED | None | `hold_decision` DEAL_CLOSED; `HoldRejected` | A late order can't leave money stuck (marked) | Done (`HoldsTest`) |
| ✅ Yes | H7 | Order | The same order reaches both instances at once | Hold call ×2 | Check 2: the second waits for the first | ➖ One answer for both | Once | One `hold_decision`; one event | One order gets one answer | Done (`HoldsTest`) |
| ✅ Yes | H8 | Order | The order uses exactly all the money | Hold call €7, has €7 | All pass | ✅ PLACED, €0 left | €7 set aside | As H1 | No off-by-one into a negative balance | To do (item 9 step 7) |
| ✅ Yes | H9 | Order | Broken request or over 64 KB | Hold call | Check 1 fails | ❌ 422 or 413 | None | Nothing saved (bad request) | Junk never reaches the books | Done (`HoldsTest`) |
| ✅ Yes | H10 | Order | Database is down | Hold call €7 | Can't check | ❌ 503 UNAVAILABLE, try again | None | Nothing saved, so a retry decides afresh | No half-saved decision | Done |
| ✅ Yes | H11 | Order | Order for €0 or a negative amount | Hold call €0 | Check 1 fails | ❌ 422 | None | Nothing saved (bad request) | A negative hold can't create money | To do: test |
| ✅ Yes | H12 | Order | Order from a student with no account yet | Hold call €7 | Account opened at €0; check 4 fails | ❌ INSUFFICIENT_FUNDS, €0 available | None | Account opened; `hold_decision`; `HoldRejected` | A missing account can't crash the call | To do: test |
| ➖ Not ours | H13 | Order | Order from a blocked account | Hold call €7 after `AccountBlocked` (Support) | Blocks aren't checked: Orders and Payments enforce them (D-A4) | ✅ PLACED if there is money | €7 set aside | As H1 | ❓ Confirm another service stops it | ❓ Open question |
| ✅ Yes | D1 | Deal | Deal goes through at €5 | `DealConfirmed` (Deals) | Seen this event? deal already settled? each hold still open? | ✅ Charged | €5 charged: restaurant €4.50, Union €0.50; €2 back to the student | `order_charge`, `deal_settlement`; `OrderCharged`, `DealSettled` | A deal settles exactly once (marked) | Done (`SettlementTest`) |
| ✅ Yes | D2 | Deal | Deal fails | `DealFailed` (Deals) | Seen? hold still open? | ✅ Released | €7 back: HELD → AVAILABLE | `deal_closure`, hold RELEASED; `HoldReleased` | Money isn't kept on a failed deal | Done (`ReleasesTest`) |
| ✅ Yes | D3 | Deal | Student pulls out of a deal | `OrderWithdrawn` (Orders) | Seen? hold still open? | ✅ Released | €7 back | `order_closure`, hold RELEASED; `HoldReleased` | Money isn't kept after leaving | Done (`ReleasesTest`) |
| ✅ Yes | D4 | Deal | Deal-confirmed message arrives twice | `DealConfirmed` again | Deal already settled | ➖ No change | None | Inbox entry only | A deal settles exactly once (marked) | Done (`SettlementTest`) |
| ✅ Yes | D5 | Deal | An instance crashes halfway through settling | `DealConfirmed` | — | ✅ Rolled back, redelivered, settled once | As D1, once | As D1, once | No half-settled deal (marked) | Done (`SettlementTest`) |
| ✅ Yes | D6 | Deal | "Failed" arrives after the deal already went through | `DealFailed` | Holds already charged | ➖ No change | None | Inbox entry only | Money isn't both charged and released | To do (item 9 step 5) |
| ✅ Yes | D7 | Deal | "Confirmed" arrives after the deal already failed | `DealConfirmed` | Holds already released | ➖ Nothing charged | None | `SettlementIssue` | Released money isn't charged | To do (item 9 step 5) |
| ✅ Yes | D8 | Deal | Student withdraws while the deal is settling, on two instances | `OrderWithdrawn` + `DealConfirmed` | Lock order (decision 0004) | ✅ Exactly one: released or charged | Once | Hold ends once | Money isn't both charged and released | To do (item 9 step 4) |
| ⚠️ Partly | D9 | Deal | The same order is listed twice in one deal | `DealConfirmed` | Duplicate in the list | ✅ First charged, problem reported | First only | `SettlementIssue` | Today the second is dropped silently | To do (item 9 step 2) |
| ⚠️ Partly | D10 | Deal | Negative price, or commission over 100% | `DealConfirmed` | Fails the contract | ❌ Dead-letter topic at once | None | Copy on `ledger.dlt` | Today it retries for 2.5 min and blocks other events | To do (item 9 step 2) |
| ✅ Yes | D11 | Deal | Deal is cancelled | `DealCancelled` (Deals) | Seen? hold still open? | ✅ Released | €7 back | `deal_closure`; `HoldReleased` DEAL_CANCELLED | Money isn't kept on a cancelled deal | To do: test |
| ✅ Yes | D12 | Deal | Orders gives up on an order | `OrderAbandoned` (Orders) | Seen? hold still open? | ✅ Released | €7 back | `order_closure`; `HoldReleased` ABANDONED | Money isn't kept on an abandoned order | To do: test |
| ✅ Yes | D13 | Deal | A hold on the deal is missing from the confirmed list | `DealConfirmed` | Open holds not on the list | ✅ Released as NOT_ON_LIST | €7 back | `HoldReleased` NOT_ON_LIST | Money isn't left set aside forever | To do: test |
| ✅ Yes | D14 | Deal | An order on the list has no money set aside | `DealConfirmed` | Hold exists and was PLACED? | ➖ Not charged, reported | None | `SettlementIssue` "No money was set aside" | Nobody is charged money they never set aside | To do: test |
| ✅ Yes | D15 | Deal | Price is higher than the money set aside (€9 vs €7) | `DealConfirmed` €9 | Set aside ≥ price? | ➖ Not charged, reported | None | `SettlementIssue` "below the price" | No overcharge, no negative balance | To do: test |
| ✅ Yes | D16 | Deal | An order on the list belongs to another student or deal | `DealConfirmed` | Same student? same deal? | ➖ Not charged, reported | None | `SettlementIssue` | The wrong student is never charged | To do: test |
| ⚠️ Partly | D17 | Deal | Deal confirmed with no orders | `DealConfirmed`, empty list | — | ➖ Settled with 0 charges | None | `DealSettled` with 0; ❓ should be `SettlementIssue` | A broken deal gets noticed | To do (item 9 step 2) |
| ✅ Yes | D18 | Deal | Order is withdrawn at the same moment its hold arrives | Hold call + `OrderWithdrawn` | Closure rows read FOR SHARE | ✅ Either refused, or placed then released | Ends with €0 set aside | Both recorded | A withdrawn order can't keep money set aside (marked) | To do (item 9 step 4) |
| ✅ Yes | D19 | Deal | 300-order deal settles while holds keep arriving | `DealConfirmed` + 100 holds/s | Lock order (decision 0004) | ✅ Settled; holds answer within 2 s | As D1 | As D1 | Settlement doesn't freeze the hold call | Done (k6, LED-22) |
| ✅ Yes | T1 | Top-up | Student tops up €10 at the desk | `TopUpRecorded` (Payments) | Amount ≥ 1? seen this topUpId? | ✅ Credited | €10 in: Union CASH → AVAILABLE | TOP_UP movement; `TopUpCredited` | — | Done (`LedgerTopUpTest`) |
| ✅ Yes | T2 | Top-up | The same top-up arrives twice | Same topUpId | Seen | ➖ No change | None | Inbox entry only | A top-up isn't credited twice | Done (`LedgerTopUpTest`) |
| ⚠️ Partly | T3 | Top-up | Same topUpId with a different amount | Same topUpId, €20 | Seen | ➖ First amount stands | None | ❓ warning log and counter | A retry can't change an amount | To do (item 9 step 3) |
| ✅ Yes | T4 | Top-up | Top-up of €0 | `TopUpRecorded` €0 | Amount fails | ❌ Dead-letter topic | None | Copy on `ledger.dlt` | Junk never reaches the books | Done (`ContractTest`) |
| ✅ Yes | T5 | Top-up | Top-up for a student with no account yet | `TopUpRecorded` €10 | Account opened at €0 | ✅ Credited | €10 in | Account opened; `TopUpCredited` | Money isn't lost if UserRegistered is late | To do: test |
| ✅ Yes | T6 | Top-up | Negative top-up | `TopUpRecorded` −€10 | Amount fails | ❌ Dead-letter topic | None | Copy on `ledger.dlt` | A "top-up" can't take money away | To do: test |
| ❌ No | W1 | Cash-out | Student asks for €20 cash and has it | `WithdrawalRequested` (Payments) | Seen this withdrawalId? enough money? | ✅ Reserved | €20 set aside | `withdrawal` RESERVED; `WithdrawalDebited` | Money being handed over can't be spent | To do (item 8) |
| ❌ No | W2 | Cash-out | Student asks for €20 but has €5 | `WithdrawalRequested` | Enough money? fails | ❌ INSUFFICIENT_FUNDS | None | `withdrawal` REFUSED; `WithdrawalRefused` | No balance ever goes below zero (marked) | To do (item 8) |
| ❌ No | W3 | Cash-out | Desk hands over the cash | `WithdrawalCompleted` | Still reserved? | ✅ Paid out | €20 leaves: HELD → Union CASH | `withdrawal` PAID_OUT; `WithdrawalPaidOut` | — | To do (item 8) |
| ❌ No | W4 | Cash-out | Desk fails to hand over the cash | `WithdrawalFailed` | Still reserved? | ✅ Returned | €20 back: HELD → AVAILABLE | `withdrawal` RETURNED; `WithdrawalReturned` | Money isn't lost when the desk fails | To do (item 8) |
| ❌ No | W5 | Cash-out | Both "completed" and "failed" arrive | Both events | The second finds it no longer reserved | ✅ First one wins | Once | One final state; one event | Cash isn't both paid and returned | To do (item 8) |
| ❌ No | W6 | Cash-out | "Completed" carries a different amount | `WithdrawalCompleted` €25 | — | ✅ The reserved €20 is used | €20 | As W3 | Never pays out more than was reserved | To do (item 8) |
| ❌ No | W7 | Cash-out | Cash-out and an order race for the same money | `WithdrawalRequested` + hold call | Both lock the student | ✅ One wins, the other is declined | Never below zero | Both recorded | No balance ever goes below zero (marked) | To do (item 9 step 4) |
| ❌ No | W8 | Cash-out | "Completed" arrives for a cash-out we never saw | `WithdrawalCompleted` | No such cash-out | ❓ | None | ❓ | Cash never reserved can't be paid out | ❓ Open question |
| ❌ No | W9 | Cash-out | The same cash-out request arrives twice | Same withdrawalId | Seen | ➖ No change | None | Nothing new | Cash isn't set aside twice | To do (item 8) |
| ❌ No | W10 | Cash-out | Same withdrawalId with another student or amount | Same withdrawalId | Seen | ➖ First stands | None | ❓ warning log and counter | A retry can't change a cash-out | To do (item 8, item 9 step 3) |
| ❌ No | W11 | Cash-out | "Completed" arrives before the request | `WithdrawalCompleted`, then `WithdrawalRequested` | No such cash-out yet | ❓ Park, or dead-letter? | None | ❓ | Cash isn't paid out twice or lost | ❓ Open question |
| ❌ No | W12 | Cash-out | Cash-out of €0 | `WithdrawalRequested` €0 | Amount fails | ❌ Dead-letter topic | None | Copy on `ledger.dlt` | Junk never reaches the books | To do (item 3 step 4) |
| ❌ No | R1 | Refund | Refund of €3 on a charged order | `RefundApproved` (Support) | Seen this refundId? charged? within the charge? | ✅ Refunded | €3 back; restaurant and Union give back their shares | `refund` PAID; `RefundPosted` | No order is refunded more than charged (marked) | To do (item 6) |
| ❌ No | R2 | Refund | Refund of €9 on a €5 charge | `RefundApproved` | Within the charge? fails | ❌ EXCEEDS_CHARGE | None | `refund` EXCEEDS_CHARGE; `RefundRefused` | No order is refunded more than charged (marked) | To do (item 6) |
| ❌ No | R3 | Refund | Two €3 refunds on a €5 charge | `RefundApproved` ×2 | Adds up earlier refunds | ✅ First paid, ❌ second EXCEEDS_CHARGE | €3 | Both recorded; `RefundPosted`, `RefundRefused` | No order is refunded more than charged (marked) | To do (item 6) |
| ❌ No | R4 | Refund | Refund arrives before the order is charged | `RefundApproved` | Charged? not yet | ⏸ Parked, paid when the charge happens | On the charge | Parked row; event on the charge | A late charge doesn't lose the refund | To do (item 6) |
| ❌ No | R5 | Refund | Refund is parked, then the order is released | Release wakes it | Charged? never | ❌ NEVER_CHARGED | None | `refund` NEVER_CHARGED; `RefundRefused` | Money that was never taken isn't refunded | To do (item 6) |
| ❌ No | R6 | Refund | Refund for an order that was never placed | `RefundApproved` | No hold, no charge | ❓ Park, or decline at once? | None | ❓ | Money isn't parked forever | ❓ Open question |
| ❌ No | R7 | Refund | The same refund arrives twice | Same refundId | Seen | ➖ No change | None | Nothing new | A refund isn't paid twice | To do (item 6) |
| ❌ No | R8 | Refund | Refund of €0 | `RefundApproved` €0 | Amount fails | ❌ Dead-letter topic | None | Copy on `ledger.dlt` | Junk never reaches the books | To do (item 6) |
| ❌ No | R9 | Refund | Refund on an order charged €0 | `RefundApproved` €3 | Within the charge? fails | ❌ EXCEEDS_CHARGE | None | `RefundRefused` | No order is refunded more than charged (marked) | To do (item 6) |
| ❌ No | R10 | Refund | Refund after the restaurant was already paid | `RefundApproved` €3 | As R1 | ✅ Refunded; restaurant payable may go negative | As R1 | As R1 | The student isn't blocked by an earlier payout | ❓ Open question |
| ❌ No | P1 | Payout | Restaurant is paid €50 | `PayoutConfirmed` (Payments) | Amount ≥ 1? seen this payoutId? | ✅ Paid | €50 leaves: PAYABLE → Union CASH | PAYOUT movement; `PayoutDebited` | — | To do (item 8) |
| ❌ No | P2 | Payout | The same payout arrives twice | Same payoutId | Seen | ➖ No change | None | Nothing new | A restaurant isn't paid twice | To do (item 8) |
| ❌ No | P3 | Payout | Payout bigger than what is owed | `PayoutConfirmed` €80, owed €50 | None (allowed) | ✅ Paid, payable goes negative | €80 | As P1 | Payments decides what to pay; the Ledger doesn't block it | To do (item 8) |
| ❌ No | P4 | Payout | Payout of €0 | `PayoutConfirmed` €0 | Amount fails | ❌ Dead-letter topic | None | Copy on `ledger.dlt` | Junk never reaches the books | To do (item 3 step 4) |
| ❌ No | P5 | Payout | Payout for a restaurant the Ledger never saw | `PayoutConfirmed` | Payable opened at €0 | ✅ Paid, payable goes negative | As P1 | As P1 | ❓ Should this be reported? | ❓ Open question |
| ✅ Yes | O1 | Outage | Kafka is down | Any | — | ✅ Events wait in the outbox, then go out in order | — | Outbox rows | No event is lost | Done |
| ✅ Yes | O2 | Outage | An instance stops after saving, before publishing | Any | — | ✅ The other instance's outbox sends it | — | Outbox rows | No event is lost | To do (item 9 step 6) |
| ✅ Yes | O3 | Outage | A top-up or release is rolled back halfway | Any | — | ✅ Redelivered, applied once | Once | As normal | Nothing is doubled | To do (item 9 step 6) |
| ✅ Yes | O4 | Outage | The sign-in key server is down | Page request | — | ❌ 503, never a wrong balance | None | Nothing | A student never sees wrong numbers | Done |
| ✅ Yes | K1 | Repeat | Kafka delivers the exact same message again | Same eventId | Seen this eventId? (inbox) | ➖ Skipped | None | Nothing new | Nothing is applied twice | Done (`LedgerTopUpTest`) |
| ✅ Yes | K2 | Repeat | A new message with the same business key | New eventId, same topUpId, orderId or dealId | Movement key (type, id) already used | ➖ No change | None | Inbox entry only | Nothing is applied twice | Done (`LedgerTopUpTest`, `SettlementTest`) |
| ✅ Yes | K3 | Repeat | Both instances get the same message (partition moved mid-way) | Same eventId twice | Inbox row in the same transaction: the second waits | ➖ Applied once | Once | One set of rows | Nothing is applied twice | To do (item 9 step 4) |
| ✅ Yes | K4 | Repeat | Ledger committed, then crashed before telling Kafka | Same eventId again | Inbox: seen | ➖ Skipped | None | Nothing new | Nothing is applied twice | To do (item 9 step 6) |
| ✅ Yes | K5 | Repeat | Orders timed out, but the Ledger had already said yes | Same orderId | Stored answer | ➖ Same answer (PLACED) | None | Nothing new | Orders is never told two things | Done (`HoldsTest`) |
| ✅ Yes | K6 | Repeat | Orders retries after a 503 | Same orderId | Nothing was saved | ✅ or ❌ decided afresh (the balance may have changed) | As H1 or H2 | As H1 or H2 | A failure never leaves a half-answer | Done |
| ✅ Yes | K7 | Repeat | The Ledger publishes an event twice (relay crashed after sending) | — | — | ➖ Same eventId sent again | None | Duplicate on `ledger.v1` | Delivery is at least once: readers dedupe by eventId | Done (platform) |
| ✅ Yes | K8 | Repeat | Student registered twice | `UserRegistered` twice | Account exists? | ➖ Opened once | None | One account | No duplicate accounts | Done (`AccountsOpenTest`) |
| ✅ Yes | K9 | Repeat | Restaurant registered twice | `RestaurantRegistered` twice | Account exists? (upsert) | ➖ Opened once | None | One payable | No duplicate accounts | To do: test |
| ⚠️ Partly | X1 | Worst case | The books stop summing to zero (a bug) | — | HANDOVER §8 SQL, run by hand | ⚠️ Found only if someone looks | — | — | Money created or lost goes unnoticed (marked) | To do (item 9 steps 1 and 8): automatic check and alert |
| ❌ No | X2 | Worst case | A bug pushes a balance below zero | — | Only the code stops it; no database CHECK | ❓ | — | — | No balance ever goes below zero (marked) | ❓ Open question: add a CHECK constraint? |
| ⚠️ Partly | X3 | Worst case | A stored balance drifts from its postings | — | Recompute SQL, run by hand | ⚠️ Found only if someone looks | — | — | Students see the wrong amount | To do (item 9 step 1, I2) |
| ✅ Yes | X4 | Worst case | Both instances are down | Any | — | Hold calls fail (Orders times out); events wait in Kafka, applied once on restart | Once, later | As normal | No money moves while nobody can check it | To do (item 4, LED-21 drill) |
| ❌ No | X5 | Worst case | Database lost and restored from a backup | — | — | ❓ Everything after the backup is gone, inbox included | ❓ | ❓ | Money changes lost or applied again | ❓ Open question: runbook |
| ❌ No | X6 | Worst case | Kafka loses messages we never read (retention, deleted topic) | — | — | ❓ Money never moves | None | Nothing | Top-ups or settlements silently missing | ❓ Open question: reconcile with Payments |
| ⚠️ Partly | X7 | Worst case | An event goes to the dead-letter topic and nobody notices | Any | — | ⚠️ Holds stay set aside | None | Copy on `ledger.dlt` | A student's money is stuck | To do (item 9 step 8, item 4: 6 h alert) |
| ⚠️ Partly | X8 | Worst case | Kafka down for hours | Any | — | ✅ Outbox keeps growing; `outbox.unpublished` gauge | None | Outbox rows | Others don't hear about money moves | To do (item 4: alert) |
| ❌ No | X9 | Worst case | A deal with 5,000 orders | `DealConfirmed` | — | ❓ One huge transaction; holds for those students may wait over 2 s | As D1 | As D1 | The hold call misses its 2 s answer | ❓ Open question: test it or set a limit |
| ⚠️ Partly | X10 | Worst case | Settlement takes over 1 s on a busy machine | `DealConfirmed`, 300 orders | — | ✅ Settled, but slow (1.1–1.2 s seen) | As D1 | As D1 | Holds slow down during settlement | To do (item 9 step 9) |
| ✅ Yes | X11 | Worst case | Two transactions deadlock | Any | Database cancels one | ✅ Hold retries within 1 s; event redelivered | Once | As normal | No stuck request | To do: test |
| ✅ Yes | X12 | Worst case | The two instances' clocks disagree | Any | The Ledger never decides on time | ✅ Same answer on both | — | — | No "timed out" on one and "fine" on the other | Done |
| ⚠️ Partly | X13 | Worst case | A balance near the top of BIGINT | Top-up | — | ⚠️ Should fail clearly to `ledger.dlt`, not retry | None | ❓ | Overflow corrupts a balance | To do (item 9 step 7) |
| ⚠️ Partly | X14 | Worst case | A student asks for someone else's Account | Page request | Student ID from the token only | ❌ 401 / 403 | None | Nothing | Privacy | Done for `/me`; to do for statement and desk (item 7) |
| ❌ No | X15 | Worst case | Student account deleted while it holds money | `DeletionApproved` (Support) | Not read by the Ledger | ❓ | ❓ | ❓ | Money left with no owner | ❓ Open question |
| ⚠️ Partly | X16 | Worst case | An event type the Ledger doesn't know arrives | Any topic | `else` branch | ⚠️ Ignored silently, no log | None | Nothing | A real money event could be missed unseen | ❓ Open question: log and count it |
| ⚠️ Partly | X17 | Worst case | A migration fails, or checksum mismatch at deploy | — | Flyway at start-up | ⚠️ Instance won't start; the other keeps serving if still up | None | — | Both instances down after a bad deploy | To do (item 4: runbook) |
| ✅ Yes | X18 | Worst case | Commission rounding leaves part of a cent | `DealConfirmed` | Whole cents only | ✅ Every cent goes to the restaurant or the Union | Sums to zero | As D1 | No cent created or lost | To do (item 9 step 7) |

## Each thing ends once

| Thing | Starts | Ends as | Never |
|---|---|---|---|
| Hold | OPEN | RELEASED or CHARGED | Both |
| Cash-out | RESERVED | PAID_OUT or RETURNED | Both |
| Refund | New or parked | PAID, EXCEEDS_CHARGE or NEVER_CHARGED | Two outcomes |

Cash-out and refund state names are planned (items 8 and 6).

## Waiting

The Ledger never declines because of time. A wait ends only when another service sends an event: Deals fails the deal, Orders abandons the order, Payments reports the desk result. A refund parked over 6 hours raises an alert, not a decline.

## Open questions

| # | Question | Owner |
|---|---|---|
| R6 | Refund for an order that was never placed: park it, or decline NEVER_CHARGED at once? | Member 6 (item 6) |
| W8 | "Completed" for an unknown cash-out: dead-letter topic or park? | Cian (item 8) |
| H4, T3 | Conflicting repeat: is a warning log and `ledger.conflicting_repeats` counter enough? | Cian (item 9) |
| R4 | What the student sees while a refund is parked | Member 6 (item 6) |
| R10 | Refund after the restaurant was paid: allow a negative payable? | Member 6 (item 6) |
| H13 | Blocked accounts: confirm Orders or Payments stops their orders | Hersh |
| W11 | "Completed" before the request: park or dead-letter? | Cian (item 8) |
| P5 | Payout for an unknown restaurant: report it? | Cian (item 8) |
| X2 | Add a database CHECK so a balance can't go below zero? | Igor (item 2) |
| X5, X6 | Runbook for a restored database or lost Kafka messages | Shakeeba (item 4) |
| X9 | Largest deal we promise to settle, and a test for it | Hersh (item 5) |
| X15 | What happens to money in a deleted account | Hersh (team spec) |
| X16 | Log and count unknown event types | Cian (item 9) |
