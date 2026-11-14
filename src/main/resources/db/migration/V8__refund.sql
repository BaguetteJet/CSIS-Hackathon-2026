-- Owner: Hersh (item 6). One row per decided refund (LED-13, LED-14, decision 0009).
--
-- refund_id is the business key: RefundApproved again, in a new envelope or on the other instance,
-- finds the row and changes nothing. A refund waiting for its charge has no row yet: it is parked.
-- order_id, deal_id, student_id are RefundApproved's own, because RefundRefused needs them even when
-- the order was never charged. The money goes to the student order_charge says paid.
--
-- outcome: POSTED, EXCEEDS_CHARGE or NEVER_CHARGED. reason: Support's, COMPLAINT or KITCHEN_FAILURE.
CREATE TABLE refund (
    refund_id                 CHAR(36)    NOT NULL PRIMARY KEY,
    order_id                  CHAR(36)    NOT NULL,
    deal_id                   CHAR(36)    NOT NULL,
    student_id                CHAR(36)    NOT NULL,
    amount_cents              BIGINT      NOT NULL,
    commission_reversed_cents BIGINT      NOT NULL DEFAULT 0,
    reason                    VARCHAR(30) NOT NULL,
    outcome                   VARCHAR(30) NOT NULL,
    decided_at                DATETIME(6) NOT NULL,
    INDEX idx_refund_order (order_id)
);
