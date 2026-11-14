# 0009 Park early refunds

**Context.** A refund can arrive before its charge (LED-14). Retrying would dead-letter it; dropping it loses money.

**Decision.** Park it with the starter's `parking`. Settlement and releases wake it through `RefundWakeups`, in their transaction. Each refund is decided once: posted, EXCEEDS_CHARGE or NEVER_CHARGED.

**Cost.** One shared hook between areas 5 and 6; an alert if parked over 6 hours.

**Check.** Item 6's tests: refund before charge, refund then release, refunds over the charge.
