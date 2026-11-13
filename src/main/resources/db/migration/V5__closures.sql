-- Owner: Hersh. Orders and deals the Ledger has heard are closed (LED-04, LED-08, LED-09).
--
-- The hold call reads these to refuse a first call for a closed order (ORDER_CLOSED) or deal
-- (DEAL_CLOSED). A row is written once, by the first closing event; repeats change nothing.
-- Releasing holds that already exist is not done here: that's the releases work after slice 1,
-- which should read and extend these tables.
--
-- reason: the event that closed it (WITHDRAWN, ABANDONED, CONFIRMED, FAILED, CANCELLED).
CREATE TABLE order_closure (
    order_id   CHAR(36)     NOT NULL PRIMARY KEY,
    deal_id    CHAR(36)     NOT NULL,
    reason     VARCHAR(30)  NOT NULL,
    closed_at  DATETIME(6)  NOT NULL
);

CREATE TABLE deal_closure (
    deal_id    CHAR(36)     NOT NULL PRIMARY KEY,
    reason     VARCHAR(30)  NOT NULL,
    closed_at  DATETIME(6)  NOT NULL
);
