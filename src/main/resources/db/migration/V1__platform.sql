-- The starter's platform tables. Do not change this file: add your own tables in V3__..., V4__...
-- All times are UTC.

-- Outbox: a service saves its change and the event in one transaction; the relay publishes them in id order.
CREATE TABLE outbox_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id      CHAR(36)     NOT NULL,
    topic         VARCHAR(100) NOT NULL,
    event_key     VARCHAR(100) NOT NULL,
    event_type    VARCHAR(100) NOT NULL,
    message_json  MEDIUMTEXT   NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    published_at  DATETIME(6)  NULL,
    CONSTRAINT uq_outbox_event_id UNIQUE (event_id),
    INDEX idx_outbox_unpublished (published_at, id)
);

-- One row: whichever instance holds it is the only relay publishing.
CREATE TABLE outbox_lease (
    name        VARCHAR(50)  NOT NULL PRIMARY KEY,
    holder      VARCHAR(200) NOT NULL,
    expires_at  DATETIME(6)  NOT NULL
);
INSERT INTO outbox_lease (name, holder, expires_at) VALUES ('outbox-relay', 'nobody', '1970-01-02 00:00:00');

-- Per-key sequence numbers, updated in the same transaction as the change.
CREATE TABLE key_sequence (
    topic          VARCHAR(100) NOT NULL,
    event_key      VARCHAR(100) NOT NULL,
    last_sequence  BIGINT       NOT NULL,
    PRIMARY KEY (topic, event_key)
);

-- Idempotent consumers: each consumer records the eventIds it has processed.
CREATE TABLE processed_event (
    consumer      VARCHAR(100) NOT NULL,
    event_id      CHAR(36)     NOT NULL,
    processed_at  DATETIME(6)  NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

-- Events that arrived before the event they depend on, waiting for it.
CREATE TABLE parked_event (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    consumer      VARCHAR(100) NOT NULL,
    waits_for     VARCHAR(200) NOT NULL,
    event_id      CHAR(36)     NOT NULL,
    message_json  MEDIUMTEXT   NOT NULL,
    parked_at     DATETIME(6)  NOT NULL,
    CONSTRAINT uq_parked UNIQUE (consumer, event_id),
    INDEX idx_parked_waiting (consumer, waits_for)
);

-- Timers: each row fires once, claimed with SELECT ... FOR UPDATE SKIP LOCKED.
CREATE TABLE timer (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    timer_type    VARCHAR(100) NOT NULL,
    timer_key     VARCHAR(200) NOT NULL,
    fire_at       DATETIME(6)  NOT NULL,
    payload       TEXT         NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    attempts      INT          NOT NULL DEFAULT 0,
    fired_at      DATETIME(6)  NULL,
    fired_by      VARCHAR(200) NULL,
    last_error    TEXT         NULL,
    CONSTRAINT uq_timer UNIQUE (timer_type, timer_key),
    INDEX idx_timer_due (status, fire_at)
);

-- Idempotency-Key: the first answer to each key, per caller.
CREATE TABLE idempotency_record (
    caller           VARCHAR(100) NOT NULL,
    idempotency_key  VARCHAR(100) NOT NULL,
    request_hash     CHAR(64)     NOT NULL,
    status           INT          NOT NULL,
    response_json    MEDIUMTEXT   NULL,
    created_at       DATETIME(6)  NOT NULL,
    PRIMARY KEY (caller, idempotency_key)
);
