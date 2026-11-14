-- Owner: Hersh. One row per order: the first answer the hold call gave (LED-03, LED-04).
--
-- The primary key on order_id is what makes retries safe. Two calls for the same order, on either
-- instance, can't both insert, so only the first one decides; every repeat reads this row back.
--
-- result: PLACED, INSUFFICIENT_FUNDS, DEAL_CLOSED or ORDER_CLOSED. PENDING only exists inside the
--         transaction that inserted the row, so nobody else ever sees it.
-- available_cents: what the student had at decision time; only set for INSUFFICIENT_FUNDS.
-- deal_id is indexed so settlement and deal closures can find a deal's holds later.
CREATE TABLE hold_decision (
    order_id         CHAR(36)     NOT NULL PRIMARY KEY,
    student_id       CHAR(36)     NOT NULL,
    deal_id          CHAR(36)     NOT NULL,
    amount_cents     BIGINT       NOT NULL,
    result           VARCHAR(30)  NOT NULL,
    available_cents  BIGINT       NULL,
    decided_at       DATETIME(6)  NOT NULL,
    INDEX idx_hold_decision_deal (deal_id)
);
