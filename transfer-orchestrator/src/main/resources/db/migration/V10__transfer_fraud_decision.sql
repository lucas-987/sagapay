-- The status cannot stand in for it: a confirmed transfer ends CLEARED whatever fraud decided.
ALTER TABLE transfers ADD COLUMN fraud_decision TEXT;
ALTER TABLE transfers ADD CONSTRAINT chk_transfers_fraud_decision
    CHECK (fraud_decision IN ('CLEARED', 'FLAGGED'));
