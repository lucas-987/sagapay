-- The ledger's own date, not a copy of its setting: the confirmation deadline must never outlive it.
ALTER TABLE transfers ADD COLUMN reservation_expires_at TIMESTAMPTZ;
