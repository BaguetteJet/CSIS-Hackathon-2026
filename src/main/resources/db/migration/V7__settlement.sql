-- What each charged order paid (LED-05). Refunds check and update their running totals here (LED-13, LED-14).
CREATE TABLE order_charge (
    order_id                  CHAR(36)    NOT NULL PRIMARY KEY,
    deal_id                   CHAR(36)    NOT NULL,
    student_id                CHAR(36)    NOT NULL,
    restaurant_id             CHAR(36)    NOT NULL,
    price_cents               BIGINT      NOT NULL,
    commission_cents          BIGINT      NOT NULL,
    returned_cents            BIGINT      NOT NULL,
    refunded_cents            BIGINT      NOT NULL DEFAULT 0,
    commission_reversed_cents BIGINT      NOT NULL DEFAULT 0,
    charged_at                DATETIME(6) NOT NULL,
    INDEX idx_order_charge_deal (deal_id)
);

-- One row per settled deal. Claiming it is what makes a deal settle once, whichever envelope or instance (LED-06, decision 0007).
CREATE TABLE deal_settlement (
    deal_id                CHAR(36)    NOT NULL PRIMARY KEY,
    restaurant_id          CHAR(36)    NOT NULL,
    settled_at             DATETIME(6) NOT NULL,
    charged_count          INT         NOT NULL DEFAULT 0,
    total_charged_cents    BIGINT      NOT NULL DEFAULT 0,
    total_commission_cents BIGINT      NOT NULL DEFAULT 0
);
