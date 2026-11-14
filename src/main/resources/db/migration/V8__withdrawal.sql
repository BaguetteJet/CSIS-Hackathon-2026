-- Owner: Cian. One row per cash-out (LED-15, LED-16), as planned in docs/schema.md §7.
--
-- The primary key on withdrawal_id makes a repeated WithdrawalRequested do nothing.
-- state: RESERVED (money set aside) or REFUSED (not enough available), then PAID_OUT or RETURNED.
--        Only a RESERVED row moves on, so a cash-out is paid out or returned, never both (like a hold, decision 0008).
--        PENDING only exists inside the transaction that inserted the row, so nobody else ever sees it.
-- amount_cents: the reserved amount; paying out or returning always uses it, not the later event's.
CREATE TABLE withdrawal (
    withdrawal_id  CHAR(36)     NOT NULL PRIMARY KEY,
    student_id     CHAR(36)     NOT NULL,
    amount_cents   BIGINT       NOT NULL,
    state          VARCHAR(20)  NOT NULL,
    decided_at     DATETIME(6)  NOT NULL
);
