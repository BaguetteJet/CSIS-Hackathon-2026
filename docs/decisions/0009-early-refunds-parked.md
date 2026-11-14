# 0009 Park early refunds

**Context.** A refund can arrive before its order was charged (LED-14).

**Decision.** Park it: save it with the starter's `parking` to handle later. When settlement or a release finishes the order, it wakes the refund through `RefundWakeups`, in the same transaction. Each refund ends one way: paid, EXCEEDS_CHARGE or NEVER_CHARGED.

**Rejected.**
- Retry until the charge exists: holds up every event behind it, then ends in the dead-letter topic.
- Refuse at once as NEVER_CHARGED: wrong when the charge is only late.
- Drop it: the student loses the money.

**Cost.** One function shared by settlement and refunds (`RefundWakeups`); an alert if a refund waits over 6 hours.

**Check.** Item 6's tests, still to write: refund before charge, refund then release, refund over the charge.
