# Schema
MySQL, migrations are in ([db/migration](../src/main/resources/db/migration/)).

## Diagrams

Solid line: foreign key. <br>
Dotted line: shared ID, no foreign key

### Ledger Tables
```mermaid
%%{init: {"theme": "default", "themeVariables": {"lineColor": "#ffffff", "relationLabelBackground": "#1e293b", "relationLabelColor": "#ffffff"}}}%%
erDiagram
    ledger_account {
        bigint id PK
        varchar owner_type UK
        char owner_id UK
        varchar purpose UK
        bigint balance_cents
        datetime created_at
    }
    ledger_movement {
        char id PK
        varchar reference_type UK
        char reference_id UK
        datetime posted_at
    }
    ledger_posting {
        bigint id PK
        char movement_id FK
        bigint account_id FK
        bigint amount_cents
        datetime posted_at
        varchar entry_kind
        varchar description
        char deal_id
        char order_id
    }
    hold_decision {
        char order_id PK
        char student_id
        char deal_id
        bigint amount_cents
        varchar result
        bigint available_cents
        datetime decided_at
        varchar state
    }
    order_closure {
        char order_id PK
        char deal_id
        varchar reason
        datetime closed_at
    }
    deal_closure {
        char deal_id PK
        varchar reason
        datetime closed_at
    }
    order_charge {
        char order_id PK
        char deal_id
        char student_id
        char restaurant_id
        bigint price_cents
        bigint commission_cents
        bigint returned_cents
        bigint refunded_cents
        bigint commission_reversed_cents
        datetime charged_at
    }
    deal_settlement {
        char deal_id PK
        char restaurant_id
        datetime settled_at
        int charged_count
        bigint total_charged_cents
        bigint total_commission_cents
    }
    known_user {
        varchar user_id PK
        varchar name
        varchar email
        varchar role
        varchar restaurant_id
        datetime updated_at
    }

    ledger_movement ||--|{ ledger_posting : movement_id
    ledger_account ||--o{ ledger_posting : account_id
    known_user |o..o{ ledger_account : owner_id
    hold_decision |o..o| order_closure : order_id
    hold_decision |o..o| order_charge : order_id
    deal_closure |o..o{ hold_decision : deal_id
    deal_settlement |o..o{ order_charge : deal_id
```

### Platform Tables
```mermaid
%%{init: {"theme": "default", "themeVariables": {"lineColor": "#ffffff", "relationLabelBackground": "#1e293b", "relationLabelColor": "#ffffff"}}}%%
erDiagram
    outbox_event {
        bigint id PK
        char event_id UK
        varchar topic
        varchar event_key
        varchar event_type
        mediumtext message_json
        datetime created_at
        datetime published_at
    }
    outbox_lease {
        varchar name PK
        varchar holder
        datetime expires_at
    }
    key_sequence {
        varchar topic PK
        varchar event_key PK
        bigint last_sequence
    }
    processed_event {
        varchar consumer PK
        char event_id PK
        datetime processed_at
    }
    parked_event {
        bigint id PK
        varchar consumer UK
        varchar waits_for
        char event_id UK
        mediumtext message_json
        datetime parked_at
    }
    timer {
        bigint id PK
        varchar timer_type UK
        varchar timer_key UK
        datetime fire_at
        text payload
        varchar status
        int attempts
        datetime fired_at
        varchar fired_by
        text last_error
    }
    idempotency_record {
        varchar caller PK
        varchar idempotency_key PK
        char request_hash
        int status
        mediumtext response_json
        datetime created_at
    }
```

## Ledger tables

### `ledger_account` (V3)

One account per owner and purpose.

| Field | Type | Attributes |
|---|---|---|
| `id` | BIGINT | PK, auto increment |
| `owner_type` | VARCHAR(20) | Unique (`owner_type`, `owner_id`, `purpose`); STUDENT, RESTAURANT, UNION |
| `owner_id` | CHAR(36) | Unique (`owner_type`, `owner_id`, `purpose`) |
| `purpose` | VARCHAR(20) | Unique (`owner_type`, `owner_id`, `purpose`); AVAILABLE, HELD, PAYABLE, CASH, COMMISSION |
| `balance_cents` | BIGINT | Default 0 |
| `created_at` | DATETIME(6) | |

### `ledger_movement` (V3)

One business movement.

| Field | Type | Attributes |
|---|---|---|
| `id` | CHAR(36) | PK |
| `reference_type` | VARCHAR(40) | Unique (`reference_type`, `reference_id`) |
| `reference_id` | CHAR(36) | Unique (`reference_type`, `reference_id`) |
| `posted_at` | DATETIME(6) | |

### `ledger_posting` (V3)

One immutable side of a movement; a movement's postings sum to zero.

| Field | Type | Attributes |
|---|---|---|
| `id` | BIGINT | PK, auto increment |
| `movement_id` | CHAR(36) | FK → `ledger_movement.id`; index |
| `account_id` | BIGINT | FK → `ledger_account.id`; index (`account_id`, `posted_at`, `id`) |
| `amount_cents` | BIGINT | Signed |
| `posted_at` | DATETIME(6) | |
| `entry_kind` | VARCHAR(30) | Nullable |
| `description` | VARCHAR(200) | Nullable |
| `deal_id` | CHAR(36) | Nullable |
| `order_id` | CHAR(36) | Nullable |

### `hold_decision` (V4, V6)

First hold answer per order, and the hold's state.

| Field | Type | Attributes |
|---|---|---|
| `order_id` | CHAR(36) | PK |
| `student_id` | CHAR(36) | |
| `deal_id` | CHAR(36) | Index; index (`deal_id`, `state`) |
| `amount_cents` | BIGINT | |
| `result` | VARCHAR(30) | PLACED, INSUFFICIENT_FUNDS, DEAL_CLOSED, ORDER_CLOSED |
| `available_cents` | BIGINT | Nullable |
| `decided_at` | DATETIME(6) | |
| `state` | VARCHAR(20) | Nullable; OPEN, RELEASED, CHARGED; index (`deal_id`, `state`) |

### `order_closure` (V5)

Orders the Ledger has seen closed.

| Field | Type | Attributes |
|---|---|---|
| `order_id` | CHAR(36) | PK |
| `deal_id` | CHAR(36) | |
| `reason` | VARCHAR(30) | WITHDRAWN, ABANDONED, CONFIRMED, FAILED, CANCELLED |
| `closed_at` | DATETIME(6) | |

### `deal_closure` (V5)

Deals the Ledger has seen closed.

| Field | Type | Attributes |
|---|---|---|
| `deal_id` | CHAR(36) | PK |
| `reason` | VARCHAR(30) | WITHDRAWN, ABANDONED, CONFIRMED, FAILED, CANCELLED |
| `closed_at` | DATETIME(6) | |

### `order_charge` (V7)

What a charged order paid, with refund totals.

| Field | Type | Attributes |
|---|---|---|
| `order_id` | CHAR(36) | PK |
| `deal_id` | CHAR(36) | Index |
| `student_id` | CHAR(36) | |
| `restaurant_id` | CHAR(36) | |
| `price_cents` | BIGINT | |
| `commission_cents` | BIGINT | |
| `returned_cents` | BIGINT | |
| `refunded_cents` | BIGINT | Default 0 |
| `commission_reversed_cents` | BIGINT | Default 0 |
| `charged_at` | DATETIME(6) | |

### `deal_settlement` (V7)

One row per settled deal.

| Field | Type | Attributes |
|---|---|---|
| `deal_id` | CHAR(36) | PK |
| `restaurant_id` | CHAR(36) | |
| `settled_at` | DATETIME(6) | |
| `charged_count` | INT | Default 0 |
| `total_charged_cents` | BIGINT | Default 0 |
| `total_commission_cents` | BIGINT | Default 0 |

### `known_user` (V2)

Users from users.v1.

| Field | Type | Attributes |
|---|---|---|
| `user_id` | VARCHAR(36) | PK |
| `name` | VARCHAR(200) | |
| `email` | VARCHAR(200) | |
| `role` | VARCHAR(30) | |
| `restaurant_id` | VARCHAR(36) | Nullable |
| `updated_at` | DATETIME(6) | |

## Platform tables (V1)

### `outbox_event`
| Field | Type | Attributes |
|---|---|---|
| `id` | BIGINT | PK, auto increment; index (`published_at`, `id`) |
| `event_id` | CHAR(36) | Unique |
| `topic` | VARCHAR(100) | |
| `event_key` | VARCHAR(100) | |
| `event_type` | VARCHAR(100) | |
| `message_json` | MEDIUMTEXT | |
| `created_at` | DATETIME(6) | |
| `published_at` | DATETIME(6) | Nullable; index (`published_at`, `id`) |

### `outbox_lease`
| Field | Type | Attributes |
|---|---|---|
| `name` | VARCHAR(50) | PK |
| `holder` | VARCHAR(200) | |
| `expires_at` | DATETIME(6) | |

### `key_sequence`
| Field | Type | Attributes |
|---|---|---|
| `topic` | VARCHAR(100) | PK (`topic`, `event_key`) |
| `event_key` | VARCHAR(100) | PK (`topic`, `event_key`) |
| `last_sequence` | BIGINT | |

### `processed_event`
| Field | Type | Attributes |
|---|---|---|
| `consumer` | VARCHAR(100) | PK (`consumer`, `event_id`) |
| `event_id` | CHAR(36) | PK (`consumer`, `event_id`) |
| `processed_at` | DATETIME(6) | |

### `parked_event`
| Field | Type | Attributes |
|---|---|---|
| `id` | BIGINT | PK, auto increment |
| `consumer` | VARCHAR(100) | Unique (`consumer`, `event_id`); index (`consumer`, `waits_for`) |
| `waits_for` | VARCHAR(200) | Index (`consumer`, `waits_for`) |
| `event_id` | CHAR(36) | Unique (`consumer`, `event_id`) |
| `message_json` | MEDIUMTEXT | |
| `parked_at` | DATETIME(6) | |

### `timer`
| Field | Type | Attributes |
|---|---|---|
| `id` | BIGINT | PK, auto increment |
| `timer_type` | VARCHAR(100) | Unique (`timer_type`, `timer_key`) |
| `timer_key` | VARCHAR(200) | Unique (`timer_type`, `timer_key`) |
| `fire_at` | DATETIME(6) | Index (`status`, `fire_at`) |
| `payload` | TEXT | Nullable |
| `status` | VARCHAR(20) | Default PENDING; index (`status`, `fire_at`) |
| `attempts` | INT | Default 0 |
| `fired_at` | DATETIME(6) | Nullable |
| `fired_by` | VARCHAR(200) | Nullable |
| `last_error` | TEXT | Nullable |

### `idempotency_record`
| Field | Type | Attributes |
|---|---|---|
| `caller` | VARCHAR(100) | PK (`caller`, `idempotency_key`) |
| `idempotency_key` | VARCHAR(100) | PK (`caller`, `idempotency_key`) |
| `request_hash` | CHAR(64) | |
| `status` | INT | |
| `response_json` | MEDIUMTEXT | Nullable |
| `created_at` | DATETIME(6) | |
