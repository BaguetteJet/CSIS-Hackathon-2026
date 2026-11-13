-- The first answer to each hold call, kept even when no money moved.
--
-- A hold that is placed has a ledger_movement (reference HOLD/orderId) and its postings; a hold
-- that is refused for insufficient funds, a closed deal or a closed order has no movement, so it
-- needs this durable decision row. order_id is the business key: a repeat with the same orderId
-- gets this first answer, whatever its body, and never moves money again.
CREATE TABLE ledger_hold_decision (
    order_id        CHAR(36)    NOT NULL PRIMARY KEY,
    deal_id         CHAR(36)    NOT NULL,
    student_id      CHAR(36)    NOT NULL,
    amount_cents    BIGINT      NOT NULL,
    result          VARCHAR(30) NOT NULL,   -- PLACED, INSUFFICIENT_FUNDS, DEAL_CLOSED, ORDER_CLOSED
    available_cents BIGINT      NOT NULL,   -- the student's balance at decision time
    held_cents      BIGINT      NOT NULL,
    decided_at      DATETIME(6) NOT NULL,
    INDEX idx_ledger_hold_student (student_id, decided_at)
);
