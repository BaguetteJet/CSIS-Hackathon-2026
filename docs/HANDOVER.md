# Ledger handover

Sections follow the template (reference, Appendix B). Item 4 owns this file; other items write their own sections.

## 1 About this service
To be written (item 4). Parts and events: [ARCHITECTURE.md](ARCHITECTURE.md).

## 2 Prerequisites
To be written (item 4).

## 3 Configuration
To be written (item 4).

## 4 Deploy
To be written (item 4): `IMAGE=cs4297.skynet.ie/hackathon-2026/line-a-ledger:release docker compose up -d --no-build`.

## 5 Verify
To be written (item 4).

## 6 Your properties
To be written (items 4, 7).

## 7 Dashboard tour
To be written (item 4).

## 8 Runbooks

Run in the Ledger database: `docker exec -it devkit-mysql-1 mysql -uroot -p ledger`.

### Where did a student's money go? (LED-24)

Balance, kept and recomputed (the two columns must match each other and `/me`):

```sql
SELECT a.purpose, a.balance_cents AS kept, COALESCE(SUM(p.amount_cents), 0) AS from_postings
FROM ledger_account a LEFT JOIN ledger_posting p ON p.account_id = a.id
WHERE a.owner_type = 'STUDENT' AND a.owner_id = '<studentId>'
GROUP BY a.id, a.purpose, a.balance_cents ORDER BY a.purpose;
```

Every movement, oldest first (`reference_id` is the topUpId, orderId, refundId...):

```sql
SELECT m.posted_at, m.reference_type, m.reference_id, a.purpose, p.amount_cents, p.description
FROM ledger_posting p JOIN ledger_account a ON a.id = p.account_id JOIN ledger_movement m ON m.id = p.movement_id
WHERE a.owner_type = 'STUDENT' AND a.owner_id = '<studentId>'
ORDER BY m.posted_at, p.id;
```

### Is the whole Ledger balanced?

All three rows must read 0.

```sql
SELECT 'all postings', COALESCE(SUM(amount_cents), 0) FROM ledger_posting
UNION ALL SELECT 'movements not summing to 0', COUNT(*) FROM (
  SELECT movement_id FROM ledger_posting GROUP BY movement_id HAVING SUM(amount_cents) <> 0) x
UNION ALL SELECT 'student balances not matching postings', COUNT(*) FROM (
  SELECT a.id FROM ledger_account a LEFT JOIN ledger_posting p ON p.account_id = a.id
  WHERE a.owner_type = 'STUDENT' GROUP BY a.id, a.balance_cents
  HAVING a.balance_cents <> COALESCE(SUM(p.amount_cents), 0)) y;
```

Union and restaurant balances are the sum of their postings (decision 0005). Their `balance_cents` stays 0: never read it.

```sql
SELECT COALESCE(SUM(p.amount_cents), 0) FROM ledger_posting p JOIN ledger_account a ON a.id = p.account_id
WHERE a.owner_type = 'RESTAURANT' AND a.owner_id = '<restaurantId>' AND a.purpose = 'PAYABLE';
```

### One deal's settlement (LED-24)

DealSettled's totals against the charges (must match):

```sql
SELECT s.charged_count, s.total_charged_cents, s.total_commission_cents,
       COUNT(c.order_id), COALESCE(SUM(c.price_cents), 0), COALESCE(SUM(c.commission_cents), 0)
FROM deal_settlement s LEFT JOIN order_charge c ON c.deal_id = s.deal_id
WHERE s.deal_id = '<dealId>' GROUP BY s.deal_id, s.charged_count, s.total_charged_cents, s.total_commission_cents;
```

Where the money went (the rows sum to 0):

```sql
SELECT a.owner_type, a.purpose, SUM(p.amount_cents)
FROM order_charge c
JOIN ledger_movement m ON m.reference_type = 'ORDER_CHARGE' AND m.reference_id = c.order_id
JOIN ledger_posting p ON p.movement_id = m.id JOIN ledger_account a ON a.id = p.account_id
WHERE c.deal_id = '<dealId>' GROUP BY a.owner_type, a.purpose;
```

Example: 11 orders at 500 with 10% commission and 600 set aside gives RESTAURANT 4950, STUDENT AVAILABLE 1100, STUDENT HELD −6600, UNION COMMISSION 550.

### Crash in settlement

Nothing to do by hand. Settlement is one transaction: a crash leaves nothing charged, and redelivery settles the deal once. Check `deal_settlement` has one row and the totals query matches. If there's no row, check the consumer is running and `ledger.dlt`.

## 9 Troubleshooting

| You see | Do |
|---|---|
| `Migration checksum mismatch for migration version 3` | Old schema in the dev kit's `ledger` database: drop and create it. Dev kit only. |
| `/me` answers 503 "Sign-in is briefly unavailable" | The sign-in key (`JWT_PUBLIC_KEY_URL`) is unreachable; it recovers once it's back. |

## 10 Known limitations
- No API for the Ledger's own totals (Union cash, commission, payables); use §8 (LED-24).

## 11 Stop and reset
To be written (item 4).
