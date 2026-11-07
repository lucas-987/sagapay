-- Text with a CHECK, like every other closed column: an enum type cannot lose a value, and adding
-- one needs a migration of its own. RESERVED goes with it: no code writes it.
DROP INDEX idx_transfers_in_flight;

ALTER TABLE transfers ALTER COLUMN status DROP DEFAULT;
ALTER TABLE transfers ALTER COLUMN status TYPE TEXT USING status::text;
ALTER TABLE transfers ALTER COLUMN status SET DEFAULT 'INITIATED';
ALTER TABLE transfers ADD CONSTRAINT chk_transfers_status CHECK (status IN (
    'INITIATED', 'SCREENING', 'BLOCKED', 'CLEARED', 'POSTED', 'FAILED'));

DROP TYPE transfer_status;

CREATE INDEX idx_transfers_in_flight ON transfers(status, updated_at)
    WHERE status IN ('INITIATED', 'SCREENING', 'BLOCKED', 'CLEARED');
