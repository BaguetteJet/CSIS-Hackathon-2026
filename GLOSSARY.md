# Ledger

The bank inside Campus Group-Buy: it decides whether a student's money is available and records every movement of money between students, restaurants and the Union. Deal terms (deal, tier, cap, cut-off, tip) are defined in the reference, Appendix C.

## Accounts

**Account**:
Where one owner's money of one kind sits. A student has two: available and set aside. A restaurant has a payable. The Union has cash and commission.
_Avoid_: wallet, balance (for the place itself)

**Available**:
The part of a student's money they can spend, set aside or cash out.

**Set aside**:
The part of a student's money held for orders whose deals are not yet decided, and (item 8) for a cash-out reserved at the desk. The word students see.
_Avoid_: hold, held, reserved (in anything a student reads)

**Payable**:
What the Union owes a restaurant: earned at settlement, reduced when Payments pays it out.

**Union cash**:
The money the Union holds for everyone: up with every desk top-up, down with every cash-out and payout.

**Commission**:
The Union's share of each charged order: the deal's rate times the price, rounded half up to the cent.

## Movements

**Movement**:
One change of money: two or more postings that add up to zero. Identified by its business key, so it happens once.
_Avoid_: transaction (that is the database's word), transfer

**Posting**:
One side of a movement: a signed amount on one account.
_Avoid_: line, leg

**Business key**:
The ID that makes an effect happen once however often it is asked for: the orderId for a hold or a charge, the topUpId for a top-up, the dealId for a settlement.
_Avoid_: idempotency key (that is the HTTP header)

**Statement entry**:
One line on a student's statement: a change to their available money, in their words.
_Avoid_: posting (in anything a student reads)

## What happens to money

**Top-up**:
Money a student hands in at the Union desk, credited to their available.

**Hold**:
Money moved from a student's available to set aside for one order, at the deal's normal price. Students see it as "set aside".
_Avoid_: reservation, authorisation

**Hold answer**:
The Ledger's one decision for an order: placed, insufficient funds, order closed or deal closed. The first answer is the answer for good.

**Closure**:
The Ledger's record that an order or a deal can no longer take money, so a later hold for it is refused.

**Release**:
A hold's money going back to available, because the order or its deal will not go ahead.
_Avoid_: refund (that is after a charge), cancellation

**Settlement**:
Turning a confirmed deal's holds into charges, exactly once, in one step.

**Charge**:
One order paying the tier price at settlement: commission to the Union, the rest of the price to the restaurant's payable.

**Returned**:
What is left of a hold after its charge, back to the student's available.
_Avoid_: change, refund

**Settlement issue**:
An order listed for charging that cannot be charged (no money set aside, already released, another deal or student, below the price). Reported to staff, never charged.

**Refund**:
Money given back after a charge, approved by Support, never more than the order was charged.
_Avoid_: release (that is before a charge)

**Cash-out**:
A student taking money out at the desk: reserved first, then paid out or returned.
_Avoid_: withdrawal (in anything a student reads; the events use it)

**Payout**:
The Union paying a restaurant what it is owed, taken from its payable once.

## Around the Ledger

**Neighbour**:
Another team's service that sends us events or reads ours. What each owns: [NEIGHBOURS.md](docs/NEIGHBOURS.md).

**Contracts**:
The organisers' library (1.3.0) that fixes every event's and API's shape. We code against it and never change it.

**Dead letter**:
An event the Ledger can't process, sent to `ledger.dlt`: at once if it breaks the contract (a zero top-up), otherwise after about 2.5 min of retries (15 min while MySQL is unreachable). Platform replays it once fixed.

**Envelope**:
The wrapper around every event: `eventId` (the inbox skips a repeat), `key` and `sequence` (order per key), `causationId` (the event that caused it).
