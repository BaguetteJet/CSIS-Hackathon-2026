-- Where each placed hold is now: OPEN, then exactly once RELEASED or CHARGED (LED-05, LED-08, LED-09).
-- Refused holds have no state. A release or a charge wins with
--   UPDATE hold_decision SET state = ? WHERE order_id = ? AND state = 'OPEN'
-- and one updated row means it won, so an order is never both released and charged.
-- (deal_id, state) finds a deal's open holds for settlement and deal releases.
ALTER TABLE hold_decision ADD COLUMN state VARCHAR(20) NULL;
UPDATE hold_decision SET state = 'OPEN' WHERE result = 'PLACED';
CREATE INDEX idx_hold_decision_deal_state ON hold_decision (deal_id, state);
