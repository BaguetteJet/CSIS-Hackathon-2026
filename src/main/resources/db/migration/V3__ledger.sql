-- The Ledger's accounts and its double-entry postings.
--
-- Every movement of money is a set of rows in ledger_posting whose signed amounts add up to
-- zero. An account's balance is the sum of its postings; ledger_account.balance_cents is that
-- sum kept up to date in the same transaction so /me and the hold call do not have to add up a
-- student's whole history on every request (handover LED-24 shows the recomputation).
--
-- All times are UTC, from the platform clock.

-- One row per account. A student has AVAILABLE and HELD accounts, so setting money aside is a
-- movement between them rather than a flag. A restaurant has one PAYABLE account. The Union has
-- a CASH account (the other side of desk money) and a COMMISSION account. The Union's two rows
-- are inserted below, before any event; the others are opened on first use.
CREATE TABLE ledger_account (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_type    VARCHAR(20)  NOT NULL,             -- STUDENT, RESTAURANT or UNION
    owner_id      CHAR(36)     NOT NULL,             -- a student/restaurant UUID, or the zero UUID for the Union
    purpose       VARCHAR(20)  NOT NULL,             -- AVAILABLE, HELD, PAYABLE, CASH or COMMISSION
    balance_cents BIGINT       NOT NULL DEFAULT 0,   -- signed: a movement's sides add up to zero
    created_at    DATETIME(6)  NOT NULL,
    CONSTRAINT uq_ledger_account UNIQUE (owner_type, owner_id, purpose)
);

-- One row per business movement (a top-up, a hold, an order charge, a refund, a cash-out, ...).
-- (reference_type, reference_id) is the business key: a repeated TopUpRecorded with the same
-- topUpId, or a repeated order charge for the same orderId, can never post a second movement.
CREATE TABLE ledger_movement (
    id             CHAR(36)    NOT NULL PRIMARY KEY,
    reference_type VARCHAR(40) NOT NULL,
    reference_id   CHAR(36)    NOT NULL,
    posted_at      DATETIME(6) NOT NULL,
    CONSTRAINT uq_ledger_movement_reference UNIQUE (reference_type, reference_id)
);

-- The two (or more) sides of a movement. Rows are immutable. entry_kind, description, deal_id
-- and order_id are set on the student's AVAILABLE side when the movement is one the student sees
-- in their statement (LED-11); other lines leave them null.
CREATE TABLE ledger_posting (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    movement_id  CHAR(36)     NOT NULL,
    account_id   BIGINT       NOT NULL,
    amount_cents BIGINT       NOT NULL,
    posted_at    DATETIME(6)  NOT NULL,
    entry_kind   VARCHAR(30)  NULL,
    description  VARCHAR(200) NULL,
    deal_id      CHAR(36)     NULL,
    order_id     CHAR(36)     NULL,
    CONSTRAINT fk_ledger_posting_movement FOREIGN KEY (movement_id) REFERENCES ledger_movement (id),
    CONSTRAINT fk_ledger_posting_account FOREIGN KEY (account_id) REFERENCES ledger_account (id),
    INDEX idx_ledger_posting_statement (account_id, posted_at, id),
    INDEX idx_ledger_posting_movement (movement_id)
);

-- The Union's accounts exist from the first start, before any event (DEC Ledger). The zero UUID
-- occupies the owner id exactly like a student's or restaurant's UUID, so the CHAR(36) column
-- never needs padding.
INSERT INTO ledger_account (owner_type, owner_id, purpose, balance_cents, created_at)
VALUES ('UNION', '00000000-0000-0000-0000-000000000000', 'CASH', 0, UTC_TIMESTAMP(6)),
       ('UNION', '00000000-0000-0000-0000-000000000000', 'COMMISSION', 0, UTC_TIMESTAMP(6));
